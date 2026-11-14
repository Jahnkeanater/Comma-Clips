"""Builds an ASS subtitle track that draws the telemetry overlay; ffmpeg burns it into the video."""
import bisect
import math
from datetime import datetime

FPS = 20
EXTRA_FIELDS = ("ay", "blink", "ped", "lat", "lon", "alt", "hdg")

# Overlay elements and whether they're on by default. GPS is off by default: it shows exactly where you were.
ELEMENTS = {
  "speed": True, "pedals": True, "engaged": True, "clock": True, "gps": False, "heading": True, "altitude": True,
  "blinkers": True, "lead": True, "accel": True, "steer": True, "wheel": True, "gforce": True,
}
COMPASS = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]


def normalize_elements(sel) -> dict:
  """Accepts a list of enabled names or a dict; unknown names are ignored."""
  if sel is None:
    return dict(ELEMENTS)
  if isinstance(sel, dict):
    return {k: bool(sel.get(k, v)) for k, v in ELEMENTS.items()}
  on = set(sel)
  return {k: k in on for k in ELEMENTS}


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
    self.extra = {k: [] for k in EXTRA_FIELDS}
    self.frames: list[list[int]] = []
    self.wall_offset = None
    for s in seg_order:
      d = segs.get(s) or {}
      if "t" in d:
        self.t += d["t"]; self.v += d["v"]; self.a += d["a"]; self.steer += d["steer"]
        self.eng += d["eng"]; self.lead += d["lead"]
        for k in EXTRA_FIELDS:  # older cached telemetry may not have these
          self.extra[k] += d.get(k) or [None] * len(d["t"])
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
    out = {"v": self.v[i], "a": self.a[i], "steer": self.steer[i], "eng": self.eng[i], "lead": self.lead[i]}
    for k in EXTRA_FIELDS:
      out[k] = self.extra[k][i]
    return out


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


def corners_geometry(W: int, H: int, el: dict | None = None) -> dict:
  """Box sizes and positions for the corner layout, given which elements are on."""
  el = normalize_elements(el)
  fs = round(H * 0.034)
  big = round(H * 0.085)
  pad = round(H * 0.025)
  ip = round(fs * 0.45)                    # inner padding of each box
  cw = 0.602                               # DejaVu Sans Mono advance width per em
  small = round(fs * 0.8)
  line_h = 1.2
  # left box lines
  left = []
  if el["speed"]:
    left.append(("speed", big * 1.12, big * cw * 3 + fs * cw * 5 + (fs * 1.2 if el["pedals"] else 0)))
  elif el["pedals"]:
    left.append(("pedals", fs * line_h, fs * cw * 8))
  if el["engaged"]:
    left.append(("engaged", fs * line_h, fs * cw * 13))
  if el["clock"]:
    left.append(("clock", small * line_h, small * cw * 19))
  if el["gps"]:
    left.append(("gps", small * line_h, small * cw * 22))
  if el["heading"] or el["altitude"]:
    n = (9 if el["heading"] else 0) + (8 if el["altitude"] else 0) + (3 if el["heading"] and el["altitude"] else 0)
    left.append(("hdgalt", small * line_h, small * cw * n))
  lw = round(max([w for _, _, w in left] or [0]) + 2 * ip) if left else 0
  lh = round(sum(h for _, h, _ in left) + 2 * ip) if left else 0
  # right box lines
  right = [k for k in ("lead", "accel", "steer") if el[k]]
  rw = round(fs * cw * 20.5 + 2 * ip) if right else 0
  rh = round(fs * line_h * len(right) + 2 * ip) if right else 0
  R = round(H * 0.06)
  gauges_y = pad + (rh + round(pad * 0.5) if right else 0) + R + ip
  wheel_cx = W - pad - R - ip
  gforce_cx = (wheel_cx - 2 * (R + ip) - round(pad * 0.5)) if el["wheel"] else wheel_cx
  return dict(fs=fs, big=big, pad=pad, ip=ip, small=small, line_h=line_h, left=left, lw=lw, lh=lh, right=right,
              rw=rw, rh=rh, rx=W - pad - rw, R=R, wheel_cx=wheel_cx, wheel_cy=gauges_y, wheel_size=2 * R,
              gforce_cx=gforce_cx, gforce_cy=gauges_y, el=el, cw=cw)


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


def _compass(deg: float) -> str:
  return COMPASS[int((deg % 360) / 22.5 + 0.5) % 16]


def _triangle(x, y, w, h, left: bool):
  return (f"m {x + w} {y} l {x} {y + h / 2} l {x + w} {y + h}" if left else f"m {x} {y} l {x + w} {y + h / 2} l {x} {y + h}")


def build_ass_corners(width: int, height: int, tel: Telemetry, first_frame: int, n_frames: int,
                      units: str = "mph", step: int = 2, draw_wheel: bool = True, elements=None) -> str:
  """Corner layout (matches the app preview). Each element can be switched off.

  Top-left: speed (+ gas/brake dot), engaged, clock, GPS, heading/altitude. Top-centre: turn signals.
  Top-right: lead, accel, steer, with the g-force dot and steering wheel underneath.
  With draw_wheel=False only the wheel's backing disc is drawn; the export lays comma's wheel image on top."""
  H, W = height, width
  g = corners_geometry(W, H, elements)
  el = g["el"]
  fs, big, pad, ip, small, cw = g["fs"], g["big"], g["pad"], g["ip"], g["small"], g["cw"]
  R = g["R"]
  BG = "&H9E&"                             # ~38% opaque black, like the app preview
  GREEN, RED, GREY, WHITE = "&H66E07A&", "&H5A5AFF&", "&HB0B0B0&", "&HFFFFFF&"
  spd_k, spd_u = (2.23694, "mph") if units == "mph" else (3.6, "km/h")
  dist_k, dist_u = (3.28084, "ft") if units == "mph" else (1.0, "m")
  ring = max(2, round(R * 0.17))

  out = [_ass_head(W, H, fs, "DejaVu Sans Mono")]
  total = _ts(n_frames / FPS + 1)
  box = "\\1c&H000000&\\1a" + BG + "\\bord0\\shad0\\p1"
  def static(x, y, shape, an=7):
    out.append(f"Dialogue: 0,0:00:00.00,{total},Draw,,0,0,0,,{{\\an{an}\\pos({x},{y}){box}}}{shape}{{\\p0}}")
  if g["left"]:
    static(pad, pad, _rect(0, 0, g["lw"], g["lh"]))
  if g["right"]:
    static(g["rx"], pad, _rect(0, 0, g["rw"], g["rh"]))
  disc = _circle(R + ip, R + ip, R + ip)
  if el["wheel"]:
    static(g["wheel_cx"], g["wheel_cy"], disc, an=5)
  if el["gforce"]:
    static(g["gforce_cx"], g["gforce_cy"], disc, an=5)
  ring_draw, spokes = _wheel_shapes(R, ring)
  # turn-signal arrows sit at the top centre
  aw, ah = round(fs * 1.4), round(fs * 1.6)
  gap = round(fs * 1.2)
  ay_ = pad + ip

  for m in range(0, n_frames, step):
    t0, t1 = _ts(m / FPS), _ts(min(m + step, n_frames) / FPS)
    mono = tel.frame_mono(first_frame + m)
    s = tel.sample(mono) if (mono is not None and tel.ok) else None
    def ev(layer, style, text):
      out.append(f"Dialogue: {layer},{t0},{t1},{style},,0,0,0,,{text}")

    # ---- left box, line by line
    y = pad + ip
    for kind, h, _ in g["left"]:
      if kind == "speed":
        sp = f"{s['v'] * spd_k:.0f}" if s else "--"
        ev(1, "Osd", f"{{\\an7\\pos({pad + ip},{round(y)})}}{{\\fs{big}}}{sp}{{\\fs{fs}}} {spd_u}")
        if el["pedals"]:
          x = pad + ip + len(sp) * big * cw + fs * cw * (len(spd_u) + 1) + fs * 0.7
          _pedal_dot(ev, s, x, y + big * 0.62, fs * 0.38, GREEN, RED)
      elif kind == "pedals":
        _pedal_dot(ev, s, pad + ip + fs * 0.4, y + fs * 0.6, fs * 0.38, GREEN, RED)
        label = "" if not s else ("brake" if s.get("ped") == 2 else ("gas" if s.get("ped") == 1 else ""))
        ev(1, "Osd", f"{{\\an7\\pos({pad + ip + round(fs * 1.1)},{round(y)})}}{label}")
      elif kind == "engaged":
        txt = "no telemetry" if s is None else (f"{{\\c{GREEN}}}● engaged" if s["eng"] else f"{{\\c{GREY}}}○ not engaged")
        ev(1, "Osd", f"{{\\an7\\pos({pad + ip},{round(y)})}}{txt}")
      elif kind == "clock":
        clock = datetime.fromtimestamp((mono + tel.wall_offset) / 1e9).strftime("%Y-%m-%d %H:%M:%S") if (mono is not None and tel.wall_offset) else ""
        ev(1, "Osd", f"{{\\an7\\pos({pad + ip},{round(y)})\\fs{small}\\c&HD0D0D0&}}{clock}")
      elif kind == "gps":
        txt = f"{s['lat']:.6f}, {s['lon']:.6f}" if (s and s.get("lat") is not None) else "no GPS fix"
        ev(1, "Osd", f"{{\\an7\\pos({pad + ip},{round(y)})\\fs{small}\\c&HD0D0D0&}}{txt}")
      elif kind == "hdgalt":
        parts = []
        if el["heading"]:
          parts.append(f"{_compass(s['hdg'])} {s['hdg']:.0f}°" if (s and s.get("hdg") is not None) else "--")
        if el["altitude"]:
          parts.append(f"{s['alt'] * dist_k:.0f} {dist_u}" if (s and s.get("alt") is not None) else "--")
        ev(1, "Osd", f"{{\\an7\\pos({pad + ip},{round(y)})\\fs{small}\\c&HD0D0D0&}}{' · '.join(parts)}")
      y += h

    if s is None:
      continue

    # ---- turn signals (flash ~1.5 times a second while on)
    if el["blinkers"]:
      b = s.get("blink") or 0
      lit = (m // 7) % 2 == 0
      for left_side, bit in ((True, 1), (False, 2)):
        x = W // 2 - gap // 2 - aw if left_side else W // 2 + gap // 2
        on = bool(b & bit) and lit
        style = f"\\1c{GREEN}\\1a&H00&" if on else "\\1c&H000000&\\1a&HB0&"
        ev(2, "Draw", f"{{\\an7\\pos({x},{ay_}){style}\\bord0\\shad0\\p1}}{_triangle(0, 0, aw, ah, left_side)}{{\\p0}}")

    # ---- right box: lead, accel, steer
    ry = pad + ip
    right_lines = []
    if el["lead"]:
      st, vl, dr = s["lead"]
      right_lines.append("LEAD " + (f"{vl * spd_k:.0f} {spd_u} · {dr * dist_k:.0f} {dist_u}" if st else "—"))
    if el["accel"]:
      a = s["a"]
      col = GREEN if a > 0.3 else (RED if a < -0.3 else WHITE)
      right_lines.append(f"ACCEL {{\\c{col}}}{_signed(a, 1)} m/s²{{\\c{WHITE}}}")
    if el["steer"]:
      right_lines.append(f"STEER {_signed(s['steer'], 0)}°")
    if right_lines:
      ev(1, "Osd", f"{{\\an9\\pos({W - pad - ip},{ry})}}" + "\\N".join(right_lines))

    # ---- g-force: dot shows the force you feel (turn left -> dot right, brake -> dot up); edge = 0.6 g
    if el["gforce"]:
      cx, cy = g["gforce_cx"], g["gforce_cy"]
      lat_g = (s.get("ay") or 0.0) / 9.81
      lon_g = s["a"] / 9.81
      scale = R / 0.6
      dx = max(-R, min(R, lat_g * scale)); dy = max(-R, min(R, lon_g * scale))
      grid = (f"m {cx - R} {cy - 0.5} l {cx + R} {cy - 0.5} l {cx + R} {cy + 0.5} l {cx - R} {cy + 0.5} "
              f"m {cx - 0.5} {cy - R} l {cx + 0.5} {cy - R} l {cx + 0.5} {cy + R} l {cx - 0.5} {cy + R}")
      ev(2, "Draw", f"{{\\an7\\pos(0,0)\\1c&HFFFFFF&\\1a&H90&\\bord0\\shad0\\p1}}{grid}{{\\p0}}")
      half = R / 2
      ev(2, "Draw", f"{{\\an7\\pos(0,0)\\1a&HFF&\\3c&HFFFFFF&\\3a&H90&\\bord1\\shad0\\p1}}{_circle(cx, cy, half)}{{\\p0}}")
      dot_r = max(3, round(R * 0.16))
      mag = math.hypot(lat_g, lon_g)
      ev(3, "Draw", f"{{\\an7\\pos(0,0)\\1c{GREEN if mag < 0.3 else ('&H4AB4F0&' if mag < 0.5 else RED)}\\bord0\\shad0\\p1}}"
                    f"{_circle(round(cx + dx), round(cy + dy), dot_r)}{{\\p0}}")
      ev(2, "Osd", f"{{\\an8\\pos({cx},{cy + R + ip + 2})\\fs{small}}}{mag:.2f} g")

    # ---- steering wheel (drawn here only when the export can't lay the comma icon on top)
    if el["wheel"] and draw_wheel:
      wcx, wcy = g["wheel_cx"], g["wheel_cy"]
      rot = f"\\an5\\pos({wcx},{wcy})\\org({wcx},{wcy})\\frz{s['steer']:.1f}"
      ev(2, "Draw", f"{{{rot}\\1a&HFF&\\3c&HFFFFFF&\\bord{ring}\\shad0\\p1}}{ring_draw}{{\\p0}}")
      ev(2, "Draw", f"{{{rot}\\1c&HFFFFFF&\\bord0\\shad0\\p1}}{spokes}{{\\p0}}")
  return "\n".join(out) + "\n"


def _pedal_dot(ev, s, x, y, r, green, red):
  """Green dot while the gas is pressed, red while braking, a faint ring otherwise."""
  ped = (s or {}).get("ped") or 0
  shape = _circle(round(x), round(y), round(r))
  if ped == 2:
    ev(2, "Draw", f"{{\\an7\\pos(0,0)\\1c{red}\\bord0\\shad0\\p1}}{shape}{{\\p0}}")
  elif ped == 1:
    ev(2, "Draw", f"{{\\an7\\pos(0,0)\\1c{green}\\bord0\\shad0\\p1}}{shape}{{\\p0}}")
  else:
    ev(2, "Draw", f"{{\\an7\\pos(0,0)\\1a&HFF&\\3c&HFFFFFF&\\3a&H80&\\bord1\\shad0\\p1}}{shape}{{\\p0}}")


def build_ass(width: int, height: int, tel: Telemetry, first_frame: int, n_frames: int,
              units: str = "mph", step: int = 2, layout: str = "panel", draw_wheel: bool = True, elements=None) -> str:
  if layout == "corners":
    return build_ass_corners(width, height, tel, first_frame, n_frames, units, step, draw_wheel, elements)
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
