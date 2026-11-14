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
  cur_lead = [0, 0.0, 0.0]
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
    elif w == "radarState":
      l1 = ev.radarState.leadOne
      cur_lead = [1 if l1.status else 0, r(l1.vLead), r(l1.dRel, 1)]
    elif w == "selfdriveState":
      engaged = 1 if ev.selfdriveState.enabled else 0
    elif w == "gpsLocationExternal" and wall_offset is None:
      g = ev.gpsLocationExternal
      if g.hasFix and g.unixTimestampMillis > 0:
        wall_offset = g.unixTimestampMillis * 1_000_000 - ev.logMonoTime
    elif w == "clocks" and wall_offset is None:
      c = ev.clocks
      if c.wallTimeNanos > 0:
        wall_offset = c.wallTimeNanos - ev.logMonoTime

  print(json.dumps({
    "seg": seg, "src": os.path.basename(path), "frames": frames,
    "t": t, "v": v, "a": a, "steer": steer, "eng": eng, "lead": lead,
    "wall_offset": wall_offset,
  }, separators=(",", ":")), flush=True)
