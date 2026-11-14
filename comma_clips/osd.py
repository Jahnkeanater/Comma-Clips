"""Builds an ASS subtitle track that draws the telemetry overlay; ffmpeg burns it into the video."""
import bisect
import math
from datetime import datetime

FPS = 20


def _ts(sec: float) -> str:
  cs = int(round(sec * 100))
  h, cs = divmod(cs, 360000)
  m, cs = divmod(cs, 6000)
  s, cs = divmod(cs, 100)
  return f"{h}:{m:02d}:{s:02d}.{cs:02d}"


class Telemetry:
  """Merged, time-sorted telemetry for several segments plus per-frame timestamps for one camera."""

  def __init__(self, segs: dict[int, dict], seg_order: list[int], camera: str):
    self.t, self.v, self.a, self.steer, self.eng, self.lead = [], [], [], [], [], []
    self.frames: list[list[int]] = []
    self.wall_offset = None
    for s in seg_order:
      d = segs.get(s) or {}
      if "t" in d:
        self.t += d["t"]; self.v += d["v"]; self.a += d["a"]; self.steer += d["steer"]
        self.eng += d["eng"]; self.lead += d["lead"]
      fr = (d.get("frames") or {}).get(camera) or []
      self.frames.append(fr)
      if self.wall_offset is None and d.get("wall_offset"):
        self.wall_offset = d["wall_offset"]
    self.ok = len(self.t) > 0

  def frame_mono(self, global_frame: int) -> int | None:
    """Monotonic time (ns) of a frame counted from the start of the first exported segment."""
    idx = global_frame
    for k, fr in enumerate(self.frames):
      n = len(fr) if fr else FPS * 60
      if idx < n:
        if fr:
          return fr[idx]
        # no encode index for this segment: extrapolate from the previous/next one
        base = self._seg_base(k)
        return None if base is None else base + int(idx * 1e9 / FPS)
      idx -= n
    return None

  def _seg_base(self, k):
    for j in range(k - 1, -1, -1):
      if self.frames[j]:
        return self.frames[j][0] + int((k - j) * 60e9)
    for j in range(k + 1, len(self.frames)):
      if self.frames[j]:
        return self.frames[j][0] - int((j - k) * 60e9)
    return None

  def sample(self, mono: int):
    i = bisect.bisect_right(self.t, mono) - 1
    if i < 0:
      i = 0
    if i >= len(self.t):
      return None
    if abs(self.t[i] - mono) > 1.5e9:  # data gap
      return None
    return {"v": self.v[i], "a": self.a[i], "steer": self.steer[i], "eng": self.eng[i], "lead": self.lead[i]}


def _signed(x: float, nd: int) -> str:
  x = round(x, nd) + 0.0  # avoid "-0"
  return f"{x:+.{nd}f}" if x != 0 else f"{0:.{nd}f}"


def _circle(cx, cy, r):
  k = 0.5523 * r
  return (f"m {cx} {cy - r} b {cx + k} {cy - r} {cx + r} {cy - k} {cx + r} {cy} "
          f"b {cx + r} {cy + k} {cx + k} {cy + r} {cx} {cy + r} "
          f"b {cx - k} {cy + r} {cx - r} {cy + k} {cx - r} {cy} "
          f"b {cx - r} {cy - k} {cx - k} {cy - r} {cx} {cy - r}")


def _rect(x0, y0, x1, y1):
  return f"m {x0} {y0} l {x1} {y0} l {x1} {y1} l {x0} {y1}"


def _ass_head(W, H, fs, font="DejaVu Sans"):
  return f"""[Script Info]
ScriptType: v4.00+
PlayResX: {W}
PlayResY: {H}
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Osd,{font},{fs},&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,1,0,0,0,100,100,0,0,1,{max(1, round(fs * 0.05))},{max(1, round(fs * 0.06))},7,0,0,0,1
Style: Draw,{font},{fs},&H00FFFFFF,&H00FFFFFF,&H00FFFFFF,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""


def _wheel_shapes(R, ring):
  D = 2 * R
  ring_draw = _circle(R, R, R - ring)
  spokes = (_rect(0 + ring, R - ring * 0.45, D - ring, R + ring * 0.45) + " " +
            _rect(R - ring * 0.45, R, R + ring * 0.45, D - ring) + " " +
            _rect(R - ring * 0.6, 0, R + ring * 0.6, ring * 2.2) + " " +
            _rect(0, 0, 0.5, 0.5) + " " + _rect(D - 0.5, D - 0.5, D, D))
  return ring_draw, spokes


def corners_geometry(W: int, H: int) -> dict:
  fs = round(H * 0.034)
  big = round(H * 0.085)
  pad = round(H * 0.025)
  ip = round(fs * 0.45)                    # inner padding of each box
  cw = 0.602                               # DejaVu Sans Mono advance width per em
  small = round(fs * 0.8)
  lw = round(max(big * cw * 3 + fs * cw * 5, fs * cw * 10, small * cw * 20) + 2 * ip)
  lh = round(big * 1.12 + fs * 1.2 + small * 1.2 + 2 * ip)
  rw = round(fs * cw * 20.5 + 2 * ip)
  rh = round(fs * 1.2 * 3 + 2 * ip)
  R = round(H * 0.06)
  wcx, wcy = W - pad - R - ip, pad + rh + round(pad * 0.5) + R + ip
  return dict(fs=fs, big=big, pad=pad, ip=ip, small=small, lw=lw, lh=lh, rw=rw, rh=rh, rx=W - pad - rw,
              R=R, wheel_cx=wcx, wheel_cy=wcy, wheel_size=2 * R)


def wheel_commands(tel: Telemetry, first_frame: int, n_frames: int, step: int = 1) -> str:
  """ffmpeg sendcmd script that turns the wheel image (rotate filter: radians, clockwise positive)."""
  lines, last = [], None
  for m in range(0, n_frames, step):
    mono = tel.frame_mono(first_frame + m)
    s = tel.sample(mono) if (mono is not None and tel.ok) else None
    ang = 0.0 if s is None else round(-math.radians(s["steer"]), 4)  # steering left (+) turns the wheel anticlockwise
    if ang != last:
      lines.append(f"{m / FPS:.3f} rotate a {ang};")
      last = ang
  return "\n".join(lines) + "\n"


def build_ass_corners(width: int, height: int, tel: Telemetry, first_frame: int, n_frames: int,
                      units: str = "mph", step: int = 2, draw_wheel: bool = True) -> str:
  """Speed top-left; lead, accel and steering top-right with the wheel under them (matches the app preview).
  With draw_wheel=False only the wheel's backing disc is drawn; the export lays comma's wheel image on top."""
  H, W = height, width
  g = corners_geometry(W, H)
  fs, big, pad, ip, small = g["fs"], g["big"], g["pad"], g["ip"], g["small"]
  lw, lh, rw, rh, rx, R = g["lw"], g["lh"], g["rw"], g["rh"], g["rx"], g["R"]
  wcx, wcy = g["wheel_cx"], g["wheel_cy"]
  BG = "&H9E&"                             # ~38% opaque black, like the app preview
  spd_k, spd_u = (2.23694, "mph") if units == "mph" else (3.6, "km/h")
  dist_k, dist_u = (3.28084, "ft") if units == "mph" else (1.0, "m")
  ring = max(2, round(R * 0.17))

  out = [_ass_head(W, H, fs, "DejaVu Sans Mono")]
  total = _ts(n_frames / FPS + 1)
  box = "\\1c&H000000&\\1a" + BG + "\\bord0\\shad0\\p1"
  out.append(f"Dialogue: 0,0:00:00.00,{total},Draw,,0,0,0,,{{\\an7\\pos({pad},{pad}){box}}}{_rect(0, 0, lw, lh)}{{\\p0}}")
  out.append(f"Dialogue: 0,0:00:00.00,{total},Draw,,0,0,0,,{{\\an7\\pos({rx},{pad}){box}}}{_rect(0, 0, rw, rh)}{{\\p0}}")
  disc = _circle(R + ip, R + ip, R + ip)
  out.append(f"Dialogue: 0,0:00:00.00,{total},Draw,,0,0,0,,{{\\an5\\pos({wcx},{wcy}){box}}}{disc}{{\\p0}}")
  ring_draw, spokes = _wheel_shapes(R, ring)

  for m in range(0, n_frames, step):
    t0, t1 = _ts(m / FPS), _ts(min(m + step, n_frames) / FPS)
    mono = tel.frame_mono(first_frame + m)
    s = tel.sample(mono) if (mono is not None and tel.ok) else None
    clock = ""
    if mono is not None and tel.wall_offset:
      clock = datetime.fromtimestamp((mono + tel.wall_offset) / 1e9).strftime("%Y-%m-%d %H:%M:%S")
    L = f"Dialogue: 1,{t0},{t1},Osd,,0,0,0,,{{\\an7\\pos({pad + ip},{pad + ip})}}"
    if s is None:
      out.append(L + f"no telemetry\\N{{\\fs{small}}}{clock}")
      continue
    eng = "{\\c&H66E07A&}● engaged{\\c&HFFFFFF&}" if s["eng"] else "{\\c&HB0B0B0&}○ not engaged{\\c&HFFFFFF&}"
    out.append(L + f"{{\\fs{big}}}{s['v'] * spd_k:.0f}{{\\fs{fs}}} {spd_u}\\N{eng}\\N{{\\fs{small}\\c&HD0D0D0&}}{clock}")
    a = s["a"]
    a_col = "&H66E07A&" if a > 0.3 else ("&H5A5AFF&" if a < -0.3 else "&HFFFFFF&")
    st, vl, dr = s["lead"]
    lead = f"{vl * spd_k:.0f} {spd_u} · {dr * dist_k:.0f} {dist_u}" if st else "—"
    out.append(f"Dialogue: 1,{t0},{t1},Osd,,0,0,0,,{{\\an9\\pos({W - pad - ip},{pad + ip})}}"
               f"LEAD {lead}\\NACCEL {{\\c{a_col}}}{_signed(a, 1)} m/s²{{\\c&HFFFFFF&}}\\NSTEER {_signed(s['steer'], 0)}°")
    if draw_wheel:
      rot = f"\\an5\\pos({wcx},{wcy})\\org({wcx},{wcy})\\frz{s['steer']:.1f}"
      out.append(f"Dialogue: 2,{t0},{t1},Draw,,0,0,0,,{{{rot}\\1a&HFF&\\3c&HFFFFFF&\\bord{ring}\\shad0\\p1}}{ring_draw}{{\\p0}}")
      out.append(f"Dialogue: 2,{t0},{t1},Draw,,0,0,0,,{{{rot}\\1c&HFFFFFF&\\bord0\\shad0\\p1}}{spokes}{{\\p0}}")
  return "\n".join(out) + "\n"


def build_ass(width: int, height: int, tel: Telemetry, first_frame: int, n_frames: int,
              units: str = "mph", step: int = 2, layout: str = "panel", draw_wheel: bool = True) -> str:
  if layout == "corners":
    return build_ass_corners(width, height, tel, first_frame, n_frames, units, step, draw_wheel)
  H, W = height, width
  fs = round(H * 0.036)          # body text size
  big = round(H * 0.080)         # speed digits
  pad = round(H * 0.025)
  R = round(H * 0.075)           # steering wheel radius
  ring = max(2, round(R * 0.17))
  box_w = round(fs * 14 + 2 * R + 3 * pad)  # widest line ~21 chars of bold DejaVu
  box_h = round(big + fs * 3.6 + 2 * pad)
  box_x, box_y = pad, H - pad - box_h
  wheel_cx = box_x + box_w - pad - R
  wheel_cy = box_y + box_h // 2
  spd_k, spd_u = (2.23694, "mph") if units == "mph" else (3.6, "km/h")
  dist_k, dist_u = (3.28084, "ft") if units == "mph" else (1.0, "m")

  head = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {W}
PlayResY: {H}
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Osd,DejaVu Sans,{fs},&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,{max(1, round(fs * 0.06))},0,7,0,0,0,1
Style: Draw,DejaVu Sans,{fs},&H00FFFFFF,&H00FFFFFF,&H00FFFFFF,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
  out = [head]
  total = _ts(n_frames / FPS + 1)
  # static translucent panel behind the gauges
  out.append(f"Dialogue: 0,0:00:00.00,{total},Draw,,0,0,0,,{{\\an7\\pos({box_x},{box_y})\\1c&H000000&\\1a&H68&\\bord0\\p1}}"
             f"{_rect(0, 0, box_w, box_h)}{{\\p0}}")
  top_box_w = round(fs * 13.5)
  out.append(f"Dialogue: 0,0:00:00.00,{total},Draw,,0,0,0,,{{\\an7\\pos({pad},{pad})\\1c&H000000&\\1a&H68&\\bord0\\p1}}"
             f"{_rect(0, 0, top_box_w, round(fs * 2.9))}{{\\p0}}")

  D = 2 * R
  ring_draw = _circle(R, R, R - ring)
  spokes = (_rect(0 + ring, R - ring * 0.45, D - ring, R + ring * 0.45) + " " +
            _rect(R - ring * 0.45, R, R + ring * 0.45, D - ring) + " " +
            _rect(R - ring * 0.6, 0, R + ring * 0.6, ring * 2.2) + " " +
            # invisible corners so both drawings share the same bounding box (keeps them centred)
            _rect(0, 0, 0.5, 0.5) + " " + _rect(D - 0.5, D - 0.5, D, D))

  for m in range(0, n_frames, step):
    t0, t1 = _ts(m / FPS), _ts(min(m + step, n_frames) / FPS)
    mono = tel.frame_mono(first_frame + m)
    s = tel.sample(mono) if (mono is not None and tel.ok) else None
    # clock + engagement
    clock = ""
    if mono is not None and tel.wall_offset:
      clock = datetime.fromtimestamp((mono + tel.wall_offset) / 1e9).strftime("%Y-%m-%d  %H:%M:%S")
    eng = ""
    if s is not None:
      eng = "{\\c&H66E07A&}● ENGAGED" if s["eng"] else "{\\c&HB0B0B0&}○ not engaged"
    out.append(f"Dialogue: 1,{t0},{t1},Osd,,0,0,0,,{{\\an7\\pos({pad + round(fs * 0.4)},{pad + round(fs * 0.3)})}}{clock}\\N{eng}")
    if s is None:
      out.append(f"Dialogue: 1,{t0},{t1},Osd,,0,0,0,,{{\\an7\\pos({box_x + pad},{box_y + pad})}}no telemetry")
      continue
    v = s["v"] * spd_k
    a = s["a"]
    a_col = "&H66E07A&" if a > 0.3 else ("&H5A5AFF&" if a < -0.3 else "&HFFFFFF&")
    st, vl, dr = s["lead"]
    lead = f"{vl * spd_k:.0f} {spd_u}  {dr * dist_k:.0f} {dist_u}" if st else "—"
    txt = (f"{{\\an7\\pos({box_x + pad},{box_y + pad})}}{{\\fs{big}}}{v:.0f}{{\\fs{fs}}} {spd_u}\\N"
           f"LEAD   {lead}\\N"
           f"ACCEL  {{\\c{a_col}}}{_signed(a, 1)} m/s²{{\\c&HFFFFFF&}}\\N"
           f"STEER  {_signed(s['steer'], 0)}°")
    out.append(f"Dialogue: 1,{t0},{t1},Osd,,0,0,0,,{txt}")
    rot = f"\\an5\\pos({wheel_cx},{wheel_cy})\\org({wheel_cx},{wheel_cy})\\frz{s['steer']:.1f}"
    out.append(f"Dialogue: 2,{t0},{t1},Draw,,0,0,0,,{{{rot}\\1a&HFF&\\3c&HFFFFFF&\\bord{ring}\\p1}}{ring_draw}{{\\p0}}")
    out.append(f"Dialogue: 2,{t0},{t1},Draw,,0,0,0,,{{{rot}\\1c&HFFFFFF&\\bord0\\p1}}{spokes}{{\\p0}}")
  return "\n".join(out) + "\n"


def wall_time(tel: Telemetry, first_frame: int) -> datetime | None:
  mono = tel.frame_mono(first_frame)
  if mono is None or not tel.wall_offset:
    return None
  return datetime.fromtimestamp((mono + tel.wall_offset) / 1e9)
