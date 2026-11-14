# Runs ON THE COMMA, piped over SSH stdin (nothing is written to the device).
# Usage: python3 - <realdata_root> <route> <seg> [<seg> ...]
# Prints one JSON object per segment on its own line.
import sys, os, json

try:
  import zstandard
  from cereal import log
except Exception as e:  # pragma: no cover - device environment problem
  print(json.dumps({"error": f"device python missing modules: {e}"}), flush=True)
  sys.exit(1)

root, route, segs = sys.argv[1], sys.argv[2], [int(s) for s in sys.argv[3:]]

ENC = {"roadEncodeIdx": "road", "wideRoadEncodeIdx": "wide", "driverEncodeIdx": "driver"}


def r(x, n=3):
  return round(float(x), n)


for seg in segs:
  path = None
  for name in ("rlog.zst", "rlog", "qlog.zst", "qlog"):
    p = os.path.join(root, f"{route}--{seg}", name)
    if os.path.exists(p):
      path = p
      break
  if path is None:
    print(json.dumps({"seg": seg, "error": "no log file"}), flush=True)
    continue
  try:
    raw = open(path, "rb").read()
    if path.endswith(".zst"):
      raw = zstandard.ZstdDecompressor().decompressobj().decompress(raw)
    events = log.Event.read_multiple_bytes(raw)
  except Exception as e:
    print(json.dumps({"seg": seg, "error": f"parse failed: {e}"}), flush=True)
    continue

  frames = {"road": [], "wide": [], "driver": []}
  t, v, a, steer, eng = [], [], [], [], []
  lead = []  # [status, vLead, dRel]
  ay, blink, ped = [], [], []     # sideways accel (m/s2, left +), blinkers (1 left, 2 right, 3 both), pedals (1 gas, 2 brake)
  lat, lon, alt, hdg = [], [], [], []
  cur_lead = [0, 0.0, 0.0]
  cur_gps = [None, None, None, None]
  yaw = 0.0
  engaged = 0
  wall_offset = None
  cs_count = 0
  for ev in events:
    w = ev.which()
    if w in ENC:
      e = getattr(ev, w)
      if e.type in ("fullHEVC", "fullH264"):
        frames[ENC[w]].append(e.timestampSof)
    elif w == "carState":
      cs_count += 1
      if cs_count % 5:  # 100 Hz -> 20 Hz
        continue
      cs = ev.carState
      t.append(ev.logMonoTime)
      v.append(r(cs.vEgo))
      a.append(r(cs.aEgo))
      steer.append(r(cs.steeringAngleDeg, 1))
      eng.append(engaged)
      lead.append(list(cur_lead))
      ay.append(r(-cs.vEgo * yaw, 2))  # yaw rate is about the device's downward axis, so left turns are negative
      blink.append((1 if cs.leftBlinker else 0) | (2 if cs.rightBlinker else 0))
      ped.append(2 if cs.brakePressed else (1 if cs.gasPressed else 0))
      lat.append(cur_gps[0]); lon.append(cur_gps[1]); alt.append(cur_gps[2]); hdg.append(cur_gps[3])
    elif w == "radarState":
      l1 = ev.radarState.leadOne
      cur_lead = [1 if l1.status else 0, r(l1.vLead), r(l1.dRel, 1)]
    elif w == "selfdriveState":
      engaged = 1 if ev.selfdriveState.enabled else 0
    elif w == "livePose":
      av = ev.livePose.angularVelocityDevice
      yaw = av.z if av.valid else 0.0
    elif w == "gpsLocationExternal":
      g = ev.gpsLocationExternal
      if g.hasFix:
        cur_gps = [round(g.latitude, 6), round(g.longitude, 6), round(g.altitude, 1), round(g.bearingDeg, 1)]
        if wall_offset is None and g.unixTimestampMillis > 0:
          wall_offset = g.unixTimestampMillis * 1_000_000 - ev.logMonoTime
      else:
        cur_gps = [None, None, None, None]
    elif w == "clocks" and wall_offset is None:
      c = ev.clocks
      if c.wallTimeNanos > 0:
        wall_offset = c.wallTimeNanos - ev.logMonoTime

  print(json.dumps({
    "v_fmt": 2, "seg": seg, "src": os.path.basename(path), "frames": frames,
    "t": t, "v": v, "a": a, "steer": steer, "eng": eng, "lead": lead,
    "ay": ay, "blink": blink, "ped": ped, "lat": lat, "lon": lon, "alt": alt, "hdg": hdg,
    "wall_offset": wall_offset,
  }, separators=(",", ":")), flush=True)
