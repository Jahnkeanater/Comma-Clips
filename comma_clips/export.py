"""Export jobs: copy full-resolution segments from the comma, join, trim, optionally burn in telemetry."""
import json
import math
import queue
import shutil
import subprocess
import tempfile
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path

from . import device
from .config import CONFIG
from .osd import FPS, Telemetry, build_ass, corners_geometry, wall_time, wheel_commands

SEG_LEN = 60.0


def have_ffmpeg() -> bool:
  return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def probe_size(path: Path) -> tuple[int, int]:
  out = subprocess.run(["ffprobe", "-v", "error", "-f", "hevc", "-select_streams", "v:0",
                        "-show_entries", "stream=width,height", "-of", "json", str(path)],
                       capture_output=True, text=True, check=True).stdout
  st = json.loads(out)["streams"][0]
  return int(st["width"]), int(st["height"])


def keyframe_times(path: Path) -> list[float]:
  out = subprocess.run(["ffprobe", "-v", "error", "-f", "hevc", "-framerate", str(FPS), "-select_streams", "v:0",
                        "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", str(path)],
                       capture_output=True, text=True).stdout
  ts = []
  for line in out.splitlines():
    parts = line.split(",")
    if len(parts) >= 2 and "K" in parts[1]:
      try:
        ts.append(float(parts[0]))
      except ValueError:
        pass
  return ts or [0.0]


class Job:
  def __init__(self, params: dict):
    self.id = uuid.uuid4().hex[:10]
    self.params = params
    self.state = "queued"       # queued | running | done | error | cancelled
    self.phase = "waiting"
    self.progress = 0.0
    self.message = ""
    self.output: Path | None = None
    self.created = time.time()
    self.finished = None
    self.cancel = threading.Event()
    self.proc: subprocess.Popen | None = None

  def to_dict(self):
    return {"id": self.id, "params": self.params, "state": self.state, "phase": self.phase,
            "progress": round(self.progress, 3), "message": self.message, "created": self.created,
            "finished": self.finished,
            "file": self.output.name if self.output else None,
            "size": self.output.stat().st_size if self.output and self.output.exists() else None}


class Cancelled(Exception):
  pass


class JobManager:
  def __init__(self):
    self.jobs: dict[str, Job] = {}
    self.q: queue.Queue[Job] = queue.Queue()
    threading.Thread(target=self._worker, daemon=True).start()

  def submit(self, params: dict) -> Job:
    params = validate(params)
    job = Job(params)
    self.jobs[job.id] = job
    self.q.put(job)
    return job

  def cancel(self, job_id: str):
    job = self.jobs.get(job_id)
    if not job:
      return
    job.cancel.set()
    if job.proc and job.proc.poll() is None:
      job.proc.terminate()
    if job.state == "queued":
      job.state = "cancelled"

  def _worker(self):
    while True:
      job = self.q.get()
      if job.cancel.is_set():
        job.state = "cancelled"
        continue
      job.state = "running"
      try:
        run_export(job)
        job.state, job.phase, job.progress = "done", "done", 1.0
      except Cancelled:
        job.state, job.message = "cancelled", "cancelled"
      except Exception as e:  # report every failure to the UI
        job.state, job.message = "error", str(e)
      job.finished = time.time()
      try:
        trim_cache()
      except Exception:
        pass


def validate(p: dict) -> dict:
  route = str(p.get("route", ""))
  if not route or "/" in route or route.count("--") != 1:
    raise ValueError("bad route name")
  camera = p.get("camera", "road")
  if camera not in device.CAMERA_FILES:
    raise ValueError("bad camera")
  start, end = float(p.get("start", 0)), float(p.get("end", 0))
  if start < 0 or end <= start:
    raise ValueError("end must be after start")
  if end - start > 3 * 3600:
    raise ValueError("clip too long (max 3 h)")
  mode = p.get("mode", "exact")
  osd = bool(p.get("osd", False))
  if osd:
    mode = "exact"  # the overlay needs a re-encode
  if mode not in ("exact", "fast"):
    raise ValueError("bad mode")
  units = "kmh" if p.get("units") == "kmh" else "mph"
  quality = p.get("quality", "high")
  if quality not in ("high", "medium", "small"):
    quality = "high"
  return {"route": route, "camera": camera, "start": start, "end": end, "mode": mode,
          "osd": osd, "units": units, "quality": quality}


def run_export(job: Job):
  if not have_ffmpeg():
    raise RuntimeError("ffmpeg is not installed on this computer (sudo apt install ffmpeg)")
  p = job.params
  route = device.get_route(p["route"])
  fname = device.CAMERA_FILES[p["camera"]]
  seg_info = {s["n"]: s for s in route["segments"]}
  k0 = int(p["start"] // SEG_LEN)
  k1 = int(math.ceil(p["end"] / SEG_LEN)) - 1
  segs = list(range(k0, k1 + 1))
  missing = [s for s in segs if s not in seg_info or fname not in seg_info[s]["files"]]
  if missing:
    raise RuntimeError(f"segments {missing} have no {p['camera']} video on the device (deleted, or not recorded)")

  fast = p["mode"] == "fast"
  w_fetch, w_tel = (0.85, 0.0) if fast else ((0.35, 0.10) if p["osd"] else (0.4, 0.0))

  # 1) copy video from the comma
  job.phase = "copying video from comma"
  sizes = [seg_info[s]["files"][fname] for s in segs]
  total = sum(sizes) or 1
  done_before = [sum(sizes[:i]) for i in range(len(segs))]
  local = []
  for i, s in enumerate(segs):
    if job.cancel.is_set():
      raise Cancelled()
    def prog(n, i=i):
      job.progress = w_fetch * (done_before[i] + n) / total
      job.message = f"{(done_before[i] + n) / 1e6:.0f} / {total / 1e6:.0f} MB"
    local.append(device.fetch_file(p["route"], s, fname, sizes[i], prog))

  # 2) telemetry (parsed on the device, read-only)
  tel = None
  if p["osd"]:
    job.phase = "reading telemetry"
    def tprog(done, n):
      job.progress = w_fetch + w_tel * done / max(n, 1)
      job.message = f"segment {done}/{n}"
    tel_data = device.fetch_telemetry(p["route"], segs, progress=tprog)
    tel = Telemetry(tel_data, segs, p["camera"])

  if job.cancel.is_set():
    raise Cancelled()

  # 3) join + trim (+ overlay)
  job.phase = "encoding" if not fast else "joining"
  job.message = ""
  offset = p["start"] - k0 * SEG_LEN
  if fast:
    keys = keyframe_times(local[0])
    offset = max([k for k in keys if k <= offset + 1e-6] or [0.0])
  duration = p["end"] - (k0 * SEG_LEN + offset)
  first_frame = int(round(offset * FPS))
  n_frames = int(round(duration * FPS))

  wt = wall_time(tel, first_frame) if tel else None
  if wt is None:
    wt = datetime.fromtimestamp(route["start_time"] + k0 * SEG_LEN + offset)
  tag = p["camera"] + ("_osd" if p["osd"] else "")
  out = CONFIG.export_dir / f"comma_{wt.strftime('%Y%m%d_%H%M%S')}_{int(duration)}s_{tag}.mp4"
  n = 2
  while out.exists():
    out = out.with_name(out.stem.rsplit("~", 1)[0] + f"~{n}.mp4"); n += 1
  tmp_out = out.with_name(out.stem + ".part.mp4")

  with tempfile.TemporaryDirectory(dir=CONFIG.cache_dir) as td:
    cmd = ["ffmpeg", "-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-progress", "pipe:1",
           "-fflags", "+genpts", "-f", "hevc", "-framerate", str(FPS),
           "-i", "concat:" + "|".join(str(x) for x in local)]
    if fast:
      cmd += ["-ss", f"{offset:.3f}", "-t", f"{duration:.3f}", "-c", "copy", "-tag:v", "hvc1"]
    else:
      crf = {"high": "18", "medium": "22", "small": "27"}[p["quality"]]
      # frame-accurate cut; timestamps restart at 0 so the overlay track lines up with the first frame
      vf = f"trim=start_frame={first_frame}:end_frame={first_frame + n_frames},setpts=PTS-STARTPTS"
      if p["osd"]:
        width, height = probe_size(local[0])
        write_osd_files(Path(td), width, height, tel, first_frame, n_frames, p["units"])
        cmd += osd_input_args()
        cmd += ["-filter_complex", osd_filter(vf, width, height), "-map", "[out]"]
      else:
        cmd += ["-vf", vf]
      cmd += ["-c:v", "libx264", "-preset", "veryfast", "-crf", crf, "-pix_fmt", "yuv420p", "-r", str(FPS)]
    cmd += ["-an", "-movflags", "+faststart", str(tmp_out)]

    job.proc = subprocess.Popen(cmd, cwd=td, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    base = w_fetch + w_tel
    for line in job.proc.stdout:
      if line.startswith("out_time_us=") or line.startswith("out_time_ms="):
        try:
          t = int(line.split("=")[1]) / 1e6
        except ValueError:
          continue
        frac = min(max(t / max(duration, 0.1), 0), 1)
        job.progress = base + (1 - base) * frac
        job.message = f"{t:.0f} / {duration:.0f} s"
      if job.cancel.is_set():
        job.proc.terminate()
    job.proc.wait()
    err = job.proc.stderr.read()
    if job.cancel.is_set():
      tmp_out.unlink(missing_ok=True)
      raise Cancelled()
    if job.proc.returncode != 0:
      tmp_out.unlink(missing_ok=True)
      raise RuntimeError("ffmpeg failed: " + (err.strip()[-400:] or f"exit {job.proc.returncode}"))
  tmp_out.rename(out)
  job.output = out
  job.message = f"{out.stat().st_size / 1e6:.0f} MB"


WHEEL_PNG = Path(__file__).parent / "assets" / "chffr_wheel.png"


def write_osd_files(td: Path, width, height, tel, first_frame, n_frames, units):
  """Overlay text/boxes as ASS plus a sendcmd script that turns comma's wheel image."""
  ass = build_ass(width, height, tel, first_frame, n_frames, units=units, layout="corners", draw_wheel=False)
  (td / "osd.ass").write_text(ass, encoding="utf-8")
  (td / "wheel.cmd").write_text(wheel_commands(tel, first_frame, n_frames), encoding="utf-8")


def osd_input_args() -> list[str]:
  return ["-loop", "1", "-framerate", str(FPS), "-i", str(WHEEL_PNG)]


def osd_filter(trim_vf: str, width: int, height: int) -> str:
  g = corners_geometry(width, height)
  S = g["wheel_size"]
  x, y = g["wheel_cx"] - S // 2, g["wheel_cy"] - S // 2
  return (f"[0:v]{trim_vf},subtitles=osd.ass[base];"
          f"[1:v]scale={S}:{S},format=rgba,sendcmd=f=wheel.cmd,rotate=a=0:c=none:ow={S}:oh={S}[wheel];"
          f"[base][wheel]overlay=x={x}:y={y}:shortest=1:format=auto[out]")


def trim_cache():
  """Keep the local copy of device files under cache_max_gb (oldest-used first)."""
  root = CONFIG.cache_dir / "files"
  if not root.exists():
    return
  files = [f for f in root.rglob("*") if f.is_file() and not f.name.endswith(".part")]
  total = sum(f.stat().st_size for f in files)
  limit = CONFIG.cache_max_gb * 1e9
  for f in sorted(files, key=lambda f: f.stat().st_atime):
    if total <= limit:
      break
    total -= f.stat().st_size
    f.unlink(missing_ok=True)


def list_exports() -> list[dict]:
  out = []
  for f in sorted(CONFIG.export_dir.glob("*.mp4"), key=lambda f: f.stat().st_mtime, reverse=True):
    if ".part" in f.name:
      continue
    out.append({"file": f.name, "size": f.stat().st_size, "mtime": f.stat().st_mtime})
  return out


MANAGER = JobManager()
