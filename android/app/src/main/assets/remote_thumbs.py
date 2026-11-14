# Runs ON THE COMMA, piped over SSH stdin (nothing is written to the device).
# Usage: python3 - <realdata_root> <route> [<route> ...]
# Prints one JSON line per route with a small JPEG thumbnail from its qlog (base64).
import sys, os, json, base64
try:
  import zstandard
  from cereal import log
except Exception as e:
  print(json.dumps({"error": f"device python missing modules: {e}"}), flush=True)
  sys.exit(1)

root, routes = sys.argv[1], sys.argv[2:]
for route in routes:
  out = {"route": route}
  segs = sorted(int(d.rsplit("--", 1)[1]) for d in os.listdir(root) if d.startswith(route + "--") and d.rsplit("--", 1)[1].isdigit())
  for seg in ([s for s in segs if s == 1] + [s for s in segs if s != 1])[:3]:
    for name in ("qlog.zst", "qlog"):
      p = os.path.join(root, f"{route}--{seg}", name)
      if not os.path.exists(p):
        continue
      try:
        raw = open(p, "rb").read()
        if name.endswith(".zst"):
          raw = zstandard.ZstdDecompressor().decompressobj().decompress(raw)
        for ev in log.Event.read_multiple_bytes(raw):
          if ev.which() == "thumbnail":
            out["jpg"] = base64.b64encode(bytes(ev.thumbnail.thumbnail)).decode()
            break
      except Exception:
        pass
      break
    if "jpg" in out:
      break
  print(json.dumps(out), flush=True)
