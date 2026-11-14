"""HTTP server: phone/desktop UI for browsing comma drives and exporting clips."""
import json
import mimetypes
import re
import socket
import subprocess
import threading
import urllib.parse
from http import cookies
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import device, export
from .config import CONFIG

STATIC = Path(__file__).parent / "static"
ROUTE_RE = re.compile(r"^[0-9a-fA-F]{8}--[0-9a-fA-F]{10}$|^[0-9]{4}-[0-9]{2}-[0-9]{2}--[0-9-]+$")
_thumb_sem = threading.Semaphore(3)
mimetypes.add_type("application/manifest+json", ".webmanifest")
mimetypes.add_type("video/mp2t", ".ts")


def check_route(name: str) -> str:
  if not ROUTE_RE.match(name):
    raise ValueError("bad route name")
  return name


class Handler(BaseHTTPRequestHandler):
  server_version = "comma-clips/1.0"
  protocol_version = "HTTP/1.1"

  def log_message(self, fmt, *args):  # quieter console
    if "/api/jobs" not in (args[0] if args else ""):
      super().log_message(fmt, *args)

  # ---------- helpers ----------
  def _authorized(self, qs) -> bool:
    if self.client_address[0] in ("127.0.0.1", "::1"):
      return True
    key = (qs.get("k") or [None])[0] or self.headers.get("X-Key")
    if not key and self.headers.get("Cookie"):
      c = cookies.SimpleCookie(self.headers["Cookie"])
      key = c["cc_key"].value if "cc_key" in c else None
    return key == CONFIG.token

  def _send(self, code, body: bytes, ctype="application/json", extra=None):
    self.send_response(code)
    self.send_header("Content-Type", ctype)
    self.send_header("Content-Length", str(len(body)))
    self.send_header("Cache-Control", "no-store" if ctype == "application/json" else "max-age=300")
    for k, v in (extra or {}).items():
      self.send_header(k, v)
    self.end_headers()
    if self.command != "HEAD":
      self.wfile.write(body)

  def _json(self, obj, code=200, extra=None):
    self._send(code, json.dumps(obj).encode(), "application/json", extra)

  def _error(self, code, msg):
    self._json({"error": msg}, code)

  def _file(self, path: Path, ctype=None, download_name=None):
    size = path.stat().st_size
    ctype = ctype or mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    start, end = 0, size - 1
    rng = self.headers.get("Range")
    code = 200
    if rng and rng.startswith("bytes="):
      a, _, b = rng[6:].split(",")[0].partition("-")
      if a:
        start = int(a)
        end = int(b) if b else size - 1
      else:
        start = max(0, size - int(b))
      end = min(end, size - 1)
      if start > end:
        self.send_response(416); self.send_header("Content-Range", f"bytes */{size}")
        self.send_header("Content-Length", "0"); self.end_headers(); return
      code = 206
    self.send_response(code)
    self.send_header("Content-Type", ctype)
    self.send_header("Accept-Ranges", "bytes")
    self.send_header("Content-Length", str(end - start + 1))
    if code == 206:
      self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
    if download_name:
      self.send_header("Content-Disposition", f'attachment; filename="{download_name}"')
    self.end_headers()
    if self.command == "HEAD":
      return
    with open(path, "rb") as f:
      f.seek(start)
      left = end - start + 1
      while left > 0:
        chunk = f.read(min(1 << 20, left))
        if not chunk:
          break
        try:
          self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
          return
        left -= len(chunk)

  # ---------- routing ----------
  def do_HEAD(self):
    self.do_GET()

  def do_GET(self):
    url = urllib.parse.urlparse(self.path)
    qs = urllib.parse.parse_qs(url.query)
    path = url.path
    if path in ("/manifest.webmanifest", "/sw.js", "/icon.svg"):
      return self._file(STATIC / path.lstrip("/"))
    if not self._authorized(qs):
      return self._send(401, (STATIC / "locked.html").read_bytes(), "text/html; charset=utf-8")
    extra = None
    if "k" in qs:  # remember the key on this phone
      extra = {"Set-Cookie": f"cc_key={CONFIG.token}; Max-Age=31536000; Path=/; SameSite=Lax"}
    try:
      if path == "/":
        return self._send(200, (STATIC / "index.html").read_bytes(), "text/html; charset=utf-8", extra)
      if path.startswith("/static/"):
        f = (STATIC / path[len("/static/"):]).resolve()
        if STATIC.resolve() not in f.parents or not f.is_file():
          return self._error(404, "not found")
        return self._file(f)
      if path == "/api/status":
        return self._json({"device": device.status(), "ffmpeg": export.have_ffmpeg(),
                           "export_dir": str(CONFIG.export_dir), "host": CONFIG.host})
      if path == "/api/settings":
        out = {"host": CONFIG.host, "user": "comma", "address": "", "ip": None, "ssh_key": CONFIG.ssh_key}
        if CONFIG.host:
          user, addr = device.parse_host(CONFIG.host)
          out |= {"user": user, "address": addr, "ip": device._resolve(addr)}
        return self._json(out)
      if path == "/api/routes":
        return self._json(device.list_routes(force="refresh" in qs))
      if path == "/api/jobs":
        return self._json([j.to_dict() for j in sorted(export.MANAGER.jobs.values(), key=lambda j: -j.created)])
      if path == "/api/exports":
        return self._json(export.list_exports())
      if path.startswith("/exports/"):
        name = urllib.parse.unquote(path[len("/exports/"):])
        f = (CONFIG.export_dir / name).resolve()
        if f.parent != CONFIG.export_dir.resolve() or not f.is_file():
          return self._error(404, "not found")
        return self._file(f, "video/mp4", download_name=name if "dl" in qs else None)
      m = re.match(r"^/api/routes/([^/]+)(/.*)?$", path)
      if m:
        return self._route_get(check_route(m.group(1)), m.group(2) or "", qs)
      return self._error(404, "not found")
    except device.DeviceError as e:
      return self._error(502, str(e))
    except ValueError as e:
      return self._error(400, str(e))

  def _route_get(self, name, sub, qs):
    if sub == "":
      return self._json(device.get_route(name))
    if sub == "/playlist.m3u8":
      r = device.get_route(name)
      lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:61", "#EXT-X-MEDIA-SEQUENCE:0",
               "#EXT-X-PLAYLIST-TYPE:VOD"]
      first = True
      for s in r["segments"]:
        if "qcamera.ts" not in s["files"]:
          continue
        if not first:
          lines.append("#EXT-X-DISCONTINUITY")
        first = False
        lines += ["#EXTINF:60.000,", f"qcam/{s['n']}.ts"]
      lines.append("#EXT-X-ENDLIST")
      return self._send(200, ("\n".join(lines) + "\n").encode(), "application/vnd.apple.mpegurl")
    m = re.match(r"^/qcam/(\d+)\.ts$", sub)
    if m:
      seg = int(m.group(1))
      r = device.get_route(name)
      info = next((s for s in r["segments"] if s["n"] == seg), None)
      if not info or "qcamera.ts" not in info["files"]:
        return self._error(404, "no preview for this segment")
      f = device.fetch_file(name, seg, "qcamera.ts", info["files"]["qcamera.ts"])
      return self._file(f, "video/mp2t")
    if sub == "/thumb.jpg":
      return self._thumb(name)
    if sub == "/telemetry":
      seg = int((qs.get("seg") or ["0"])[0])
      data = device.fetch_telemetry(name, [seg])
      return self._json(data.get(seg, {}))
    return self._error(404, "not found")

  def _thumb(self, name):
    out = CONFIG.cache_dir / "thumbs" / f"{name}.jpg"
    if not out.exists():
      if not export.have_ffmpeg():
        return self._error(404, "no ffmpeg")
      r = device.get_route(name)
      segs = [s for s in r["segments"] if "qcamera.ts" in s["files"]]
      if not segs:
        return self._error(404, "no preview")
      s = segs[min(1, len(segs) - 1)]
      with _thumb_sem:
        if not out.exists():
          src = device.fetch_file(name, s["n"], "qcamera.ts", s["files"]["qcamera.ts"])
          out.parent.mkdir(parents=True, exist_ok=True)
          tmp = out.with_suffix(".tmp.jpg")
          subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", "20", "-i", str(src), "-frames:v", "1",
                          "-vf", "scale=480:-2", "-q:v", "5", str(tmp)], timeout=30)
          if not tmp.exists():
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(src), "-frames:v", "1",
                            "-vf", "scale=480:-2", "-q:v", "5", str(tmp)], timeout=30)
          if tmp.exists():
            tmp.rename(out)
    if not out.exists():
      return self._error(404, "thumbnail failed")
    return self._file(out, "image/jpeg")

  def do_POST(self):
    url = urllib.parse.urlparse(self.path)
    qs = urllib.parse.parse_qs(url.query)
    if not self._authorized(qs):
      return self._error(401, "locked")
    n = int(self.headers.get("Content-Length") or 0)
    try:
      body = json.loads(self.rfile.read(n) or b"{}")
    except json.JSONDecodeError:
      return self._error(400, "bad json")
    try:
      if url.path == "/api/export":
        job = export.MANAGER.submit(body)
        return self._json(job.to_dict(), 201)
      if url.path == "/api/settings/scan":
        return self._json(device.scan_network())
      if url.path == "/api/settings/test":
        return self._json(device.test_host(str(body.get("host", ""))))
      if url.path == "/api/settings/trust":
        return self._json(device.trust_host(str(body.get("host", "")), str(body.get("fingerprint", ""))))
      if url.path == "/api/settings/key":
        return self._json(device.set_ssh_key(str(body.get("path", ""))))
      if url.path == "/api/settings/key/check":
        return self._json(device.check_key(str(body.get("path", ""))))
      if url.path == "/api/settings/save":
        return self._json(device.set_host(str(body.get("host", ""))))
      m = re.match(r"^/api/jobs/([0-9a-f]+)/cancel$", url.path)
      if m:
        export.MANAGER.cancel(m.group(1))
        return self._json({"ok": True})
      if url.path == "/api/exports/delete":
        name = str(body.get("file", ""))
        f = (CONFIG.export_dir / name).resolve()
        if f.parent != CONFIG.export_dir.resolve() or not f.is_file() or f.suffix != ".mp4":
          return self._error(404, "not found")
        f.unlink()
        return self._json({"ok": True})
      return self._error(404, "not found")
    except ValueError as e:
      return self._error(400, str(e))


def lan_ip() -> str:
  s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
  try:
    s.connect((CONFIG.host.split("@")[-1] if CONFIG.host else "10.255.255.255", 22))  # no packets are sent
    return s.getsockname()[0]
  except OSError:
    return "127.0.0.1"
  finally:
    s.close()


def main():
  srv = ThreadingHTTPServer((CONFIG.listen, CONFIG.port), Handler)
  srv.daemon_threads = True
  ip = lan_ip()
  print("comma-clips is running")
  print(f"  this computer: http://localhost:{CONFIG.port}/")
  print(f"  phone (same Wi-Fi): http://{ip}:{CONFIG.port}/?k={CONFIG.token}")
  print(f"  exports are saved to {CONFIG.export_dir}")
  if not CONFIG.host:
    print("  first run: open the page and use Connection (gear button) to find your comma")
  if not export.have_ffmpeg():
    print("  WARNING: ffmpeg not found - install it with: sudo apt install ffmpeg")
  try:
    srv.serve_forever()
  except KeyboardInterrupt:
    pass


if __name__ == "__main__":
  main()
