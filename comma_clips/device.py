"""Read-only access to the comma device over SSH. Nothing is ever written to the device."""
import json
import os
import shlex
import subprocess
import threading
import time
from pathlib import Path

from .config import CONFIG

HERE = Path(__file__).parent
REMOTE_SCRIPT = (HERE / "remote_telemetry.py").read_bytes()
CAMERA_FILES = {"road": "fcamera.hevc", "wide": "ecamera.hevc", "driver": "dcamera.hevc"}


class DeviceError(Exception):
  pass


APP_KNOWN_HOSTS = Path.home() / ".config" / "comma-clips" / "known_hosts"
USER_KNOWN_HOSTS = Path.home() / ".ssh" / "known_hosts"


def _control_path() -> str:
  """Socket for SSH connection sharing. Unix sockets are limited to ~107 bytes, so use ssh's short %C hash
  and fall back to a private folder in /tmp when the cache folder path is long."""
  p = CONFIG.cache_dir / "ssh-%C"
  if len(str(p)) + 40 < 104:  # %C expands to 40 hex chars
    return str(p)
  d = Path("/tmp") / f"comma-clips-{os.getuid()}"
  d.mkdir(mode=0o700, exist_ok=True)
  return str(d / "%C")


def _ssh_opts(host: str, persist=True) -> list[str]:
  opts = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=6", "-o", "StrictHostKeyChecking=yes",
          "-o", f"UserKnownHostsFile={USER_KNOWN_HOSTS} {APP_KNOWN_HOSTS}"]
  if CONFIG.ssh_key:
    opts += ["-i", str(Path(CONFIG.ssh_key).expanduser()), "-o", "IdentitiesOnly=yes"]
  if persist:
    opts += ["-o", "ControlMaster=auto", "-o", f"ControlPath={_control_path()}", "-o", "ControlPersist=120"]
  else:
    opts += ["-o", "ControlPath=none"]
  return opts + [host]


def _ssh_base():
  if not CONFIG.host:
    raise DeviceError("No comma set up yet. Open Connection (the gear button) to find or enter it.")
  return _ssh_opts(CONFIG.host)


def ssh_run(cmd: str, timeout=60, stdin: bytes | None = None) -> str:
  try:
    p = subprocess.run(_ssh_base() + [cmd], input=stdin, capture_output=True, timeout=timeout)
  except subprocess.TimeoutExpired:
    raise DeviceError("comma did not respond (timeout)")
  if p.returncode != 0:
    err = p.stderr.decode(errors="replace").strip().splitlines()
    raise DeviceError(err[-1] if err else f"ssh exited {p.returncode}")
  return p.stdout.decode(errors="replace")


def status() -> dict:
  if not CONFIG.host:
    return {"online": False, "configured": False, "error": "no comma set up yet"}
  try:
    out = ssh_run(f"cat /data/params/d/IsOnroad 2>/dev/null; echo; df -h {shlex.quote(CONFIG.realdata)} | tail -1", timeout=10)
    lines = out.splitlines()
    return {"online": True, "onroad": lines[0].strip() == "1", "disk": lines[-1].split() if lines else []}
  except DeviceError as e:
    return {"online": False, "error": str(e)}


_routes_cache = {"t": 0.0, "data": None}
_routes_lock = threading.Lock()


def list_routes(force=False) -> list[dict]:
  with _routes_lock:
    if not force and _routes_cache["data"] is not None and time.time() - _routes_cache["t"] < 30:
      return _routes_cache["data"]
    out = ssh_run(f"cd {shlex.quote(CONFIG.realdata)} && find . -mindepth 2 -maxdepth 2 -type f -printf '%h %f %s %T@\\n'", timeout=60)
    routes: dict[str, dict] = {}
    for line in out.splitlines():
      try:
        d, f, size, mtime = line.split(" ")
      except ValueError:
        continue
      d = d[2:] if d.startswith("./") else d
      if d.count("--") != 2:
        continue
      name, seg = d.rsplit("--", 1)
      try:
        seg = int(seg)
      except ValueError:
        continue
      rt = routes.setdefault(name, {"name": name, "segments": {}})
      s = rt["segments"].setdefault(seg, {"files": {}, "mtime": 0.0})
      s["files"][f] = int(size)
      s["mtime"] = max(s["mtime"], float(mtime))
    result = []
    for rt in routes.values():
      segs = rt["segments"]
      nums = sorted(segs)
      first = segs[nums[0]]
      cams = [c for c, fn in CAMERA_FILES.items() if any(fn in segs[n]["files"] for n in nums)]
      result.append({
        "name": rt["name"],
        "segments": [{"n": n, "files": segs[n]["files"], "mtime": segs[n]["mtime"]} for n in nums],
        "first_seg": nums[0], "last_seg": nums[-1], "count": len(nums),
        # segment directories are last written when the segment closes, ~60 s after it started
        "start_time": first["mtime"] - 60 * (nums[0] + 1),
        "end_time": segs[nums[-1]]["mtime"],
        "duration": (nums[-1] + 1) * 60,
        "cameras": cams,
        "has_qcam": any("qcamera.ts" in segs[n]["files"] for n in nums),
      })
    result.sort(key=lambda r: r["start_time"], reverse=True)
    _routes_cache.update(t=time.time(), data=result)
    return result


def get_route(name: str) -> dict:
  for r in list_routes():
    if r["name"] == name:
      return r
  for r in list_routes(force=True):
    if r["name"] == name:
      return r
  raise DeviceError(f"route {name} not found on device")


def remote_path(route: str, seg: int, fname: str) -> str:
  return f"{CONFIG.realdata}/{route}--{seg}/{fname}"


_fetch_locks: dict[str, threading.Lock] = {}
_fetch_locks_lock = threading.Lock()


def fetch_file(route: str, seg: int, fname: str, expected_size: int | None = None, progress=None) -> Path:
  """Copy a file from the device into the local cache (once) and return the local path."""
  dst = CONFIG.cache_dir / "files" / route / str(seg) / fname
  key = str(dst)
  with _fetch_locks_lock:
    lock = _fetch_locks.setdefault(key, threading.Lock())
  with lock:
    if dst.exists() and (expected_size is None or dst.stat().st_size == expected_size):
      if progress:
        progress(dst.stat().st_size)
      return dst
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(dst.suffix + ".part")
    cmd = _ssh_base() + ["cat " + shlex.quote(remote_path(route, seg, fname))]
    got = 0
    with open(tmp, "wb") as out, subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as p:
      while True:
        chunk = p.stdout.read(1 << 20)
        if not chunk:
          break
        out.write(chunk)
        got += len(chunk)
        if progress:
          progress(got)
      p.wait()
      if p.returncode != 0:
        tmp.unlink(missing_ok=True)
        raise DeviceError(f"could not copy {fname} for segment {seg}: {p.stderr.read().decode(errors='replace').strip()}")
    os.replace(tmp, dst)
    return dst


def fetch_telemetry(route: str, segs: list[int], allow_onroad=False, progress=None) -> dict[int, dict]:
  """Parse the logs for the given segments on the device (read-only) and cache the result locally."""
  cache = CONFIG.cache_dir / "telemetry" / route
  cache.mkdir(parents=True, exist_ok=True)
  result, todo = {}, []
  for s in segs:
    f = cache / f"{s}.json"
    if f.exists():
      result[s] = json.loads(f.read_text())
    else:
      todo.append(s)
  if progress:
    progress(len(result), len(segs))
  if not todo:
    return result
  if not allow_onroad and status().get("onroad"):
    raise DeviceError("the comma is driving right now; telemetry is only read while it's parked")
  args = " ".join([shlex.quote(CONFIG.realdata), shlex.quote(route)] + [str(s) for s in todo])
  remote = (f"cd {shlex.quote(CONFIG.openpilot)} && PYTHONPATH={shlex.quote(CONFIG.pythonpath)} "
            f"nice -n 19 {shlex.quote(CONFIG.python)} - {args}")
  p = subprocess.Popen(_ssh_base() + [remote], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
  p.stdin.write(REMOTE_SCRIPT)
  p.stdin.close()
  for line in p.stdout:
    try:
      d = json.loads(line)
    except json.JSONDecodeError:
      continue
    if "error" in d and "seg" not in d:
      raise DeviceError(d["error"])
    s = d.get("seg")
    if s is None:
      continue
    if "error" not in d:
      (cache / f"{s}.json").write_text(json.dumps(d, separators=(",", ":")))
    result[s] = d
    if progress:
      progress(len(result), len(segs))
  p.wait()
  if p.returncode != 0 and len(result) < len(segs):
    raise DeviceError("telemetry read failed: " + p.stderr.read().decode(errors="replace").strip()[-300:])
  return result


# ---------------------------------------------------------------- connection settings / discovery
import ipaddress
import re
import socket
from concurrent.futures import ThreadPoolExecutor

HOST_RE = re.compile(r"^(?:([a-z_][a-z0-9_-]{0,31})@)?([A-Za-z0-9][A-Za-z0-9.-]{0,252}|\d{1,3}(?:\.\d{1,3}){3})$")


def parse_host(s: str) -> tuple[str, str]:
  """'comma@1.2.3.4' / '1.2.3.4' / 'comma-xxxx.local' -> ('comma', address)."""
  m = HOST_RE.match((s or "").strip())
  if not m:
    raise ValueError("enter an address like 192.168.1.50 or comma-xxxxxxxx.local")
  return m.group(1) or "comma", m.group(2)


def _resolve(addr: str) -> str | None:
  try:
    return socket.gethostbyname(addr)
  except OSError:
    return None


def _port_open(ip: str, port=22, timeout=0.5) -> bool:
  try:
    with socket.create_connection((ip, port), timeout=timeout):
      return True
  except OSError:
    return False


def _keyscan(addr: str) -> list[tuple[str, str]]:
  """Host keys offered by addr as (type, base64 blob)."""
  try:
    out = subprocess.run(["ssh-keyscan", "-T", "3", addr], capture_output=True, text=True, timeout=10).stdout
  except subprocess.TimeoutExpired:
    return []
  keys = []
  for line in out.splitlines():
    parts = line.split()
    if len(parts) >= 3 and not line.startswith("#"):
      keys.append((parts[1], parts[2]))
  return keys


def _fingerprint(ktype: str, blob: str) -> str:
  p = subprocess.run(["ssh-keygen", "-lf", "-"], input=f"x {ktype} {blob}\n", capture_output=True, text=True)
  parts = p.stdout.split()
  return parts[1] if len(parts) > 1 else "?"


def _trusted_blobs() -> set[str]:
  blobs = set()
  for f in (USER_KNOWN_HOSTS, APP_KNOWN_HOSTS):
    try:
      for line in f.read_text().splitlines():
        parts = line.split()
        if len(parts) >= 3 and not line.startswith("#"):
          i = 2 if not parts[0].startswith("@") else 3
          if len(parts) > i:
            blobs.add(parts[i])
    except OSError:
      pass
  return blobs


def _known_for(addr: str) -> bool:
  for f in (USER_KNOWN_HOSTS, APP_KNOWN_HOSTS):
    if f.exists() and subprocess.run(["ssh-keygen", "-F", addr, "-f", str(f)], capture_output=True).returncode == 0:
      return True
  return False


def _remember(addr: str, keys: list[tuple[str, str]]):
  APP_KNOWN_HOSTS.parent.mkdir(parents=True, exist_ok=True)
  with open(APP_KNOWN_HOSTS, "a") as f:
    for t, b in keys:
      f.write(f"{addr} {t} {b}\n")


def _reverse_name(ip: str) -> str | None:
  try:
    return socket.gethostbyaddr(ip)[0]
  except OSError:
    return None


def test_host(host: str) -> dict:
  """Check that host is reachable, is trusted, and looks like a comma with drives."""
  user, addr = parse_host(host)
  host = f"{user}@{addr}"
  res = {"host": host, "address": addr, "ip": _resolve(addr)}
  if not res["ip"]:
    return res | {"ok": False, "stage": "resolve", "error": f"can't find {addr} on the network"}
  if not _port_open(res["ip"]):
    return res | {"ok": False, "stage": "reach", "error": f"{addr} ({res['ip']}) isn't answering on SSH port 22"}
  keys = _keyscan(res["ip"])  # by IP: ssh-keyscan can't resolve .local names
  if not keys:
    return res | {"ok": False, "stage": "reach", "error": "couldn't read the device's SSH key"}
  if not _known_for(addr):
    trusted = _trusted_blobs()
    if any(b in trusted for _, b in keys):
      _remember(addr, keys)  # same device you already trust under another name/address
      res["remembered"] = True
    else:
      k = next((k for k in keys if k[0] == "ssh-ed25519"), keys[0])
      return res | {"ok": False, "stage": "trust", "key_type": k[0], "fingerprint": _fingerprint(*k),
                    "error": "this device's SSH key isn't trusted yet"}
  try:
    p = subprocess.run(_ssh_opts(host, persist=False) +
                       [f"cat /data/params/d/DongleId 2>/dev/null; echo; hostname; test -d {shlex.quote(CONFIG.realdata)} && echo DATA"],
                       capture_output=True, text=True, timeout=15)
  except subprocess.TimeoutExpired:
    return res | {"ok": False, "stage": "login", "error": "login timed out"}
  if p.returncode != 0:
    err = (p.stderr.strip().splitlines() or ["login failed"])[-1]
    return res | {"ok": False, "stage": "login", "error": f"couldn't log in as {user}: {err}"}
  lines = p.stdout.splitlines()
  res |= {"dongle_id": (lines[0].strip() if lines else ""), "hostname": (lines[1].strip() if len(lines) > 1 else "")}
  if "DATA" not in lines:
    return res | {"ok": False, "stage": "data", "error": "logged in, but there's no drive folder - is this a comma?"}
  return res | {"ok": True}


def trust_host(host: str, fingerprint: str) -> dict:
  """Remember the device's key after the user confirmed its fingerprint."""
  user, addr = parse_host(host)
  ip = _resolve(addr)
  keys = _keyscan(ip) if ip else []
  if not any(_fingerprint(t, b) == fingerprint for t, b in keys):
    raise ValueError("the device's key changed since you looked at it; search again")
  _remember(addr, keys)
  return test_host(f"{user}@{addr}")


def set_host(host: str) -> dict:
  res = test_host(host)
  if not res.get("ok"):
    raise ValueError(res.get("error", "connection test failed"))
  _close_master()
  CONFIG.host = res["host"]
  CONFIG.save_value("host", res["host"])
  with _routes_lock:
    _routes_cache.update(t=0.0, data=None)
  return res


def _local_networks() -> list[ipaddress.IPv4Network]:
  nets = []
  try:
    out = subprocess.run(["ip", "-4", "-o", "addr", "show", "scope", "global"], capture_output=True, text=True).stdout
  except FileNotFoundError:
    out = ""
  for line in out.splitlines():
    parts = line.split()
    if len(parts) < 4 or parts[1].startswith(("docker", "br-", "veth", "virbr", "tailscale", "zt")):
      continue
    iface = ipaddress.IPv4Interface(parts[3])
    if not iface.network.is_private:
      continue
    net = iface.network if iface.network.prefixlen >= 22 else ipaddress.IPv4Network(f"{iface.ip}/24", strict=False)
    nets.append(net)
  return nets


def scan_network() -> list[dict]:
  """Find devices on the local network that accept SSH and say which look like a comma."""
  hosts = [str(ip) for net in _local_networks() for ip in net.hosts()]
  with ThreadPoolExecutor(max_workers=128) as ex:
    open_ips = [ip for ip, ok in zip(hosts, ex.map(lambda ip: _port_open(ip, 22, 0.6), hosts)) if ok]
  trusted = _trusted_blobs()
  current_ip = _resolve(parse_host(CONFIG.host)[1]) if CONFIG.host else None

  def describe(ip):
    name = _reverse_name(ip)
    keys = _keyscan(ip)
    short = name.split(".")[0] if name else None
    known = any(b in trusted for _, b in keys)
    looks = bool(short and short.lower().startswith("comma-"))
    suggest = f"{short}.local" if looks else ip
    if looks and _resolve(suggest) != ip:
      suggest = ip
    return {"ip": ip, "name": name, "suggest": suggest, "comma": looks, "trusted": known, "current": ip == current_ip}

  with ThreadPoolExecutor(max_workers=16) as ex:
    found = list(ex.map(describe, open_ips))
  found.sort(key=lambda d: (not d["current"], not d["comma"], not d["trusted"], ipaddress.IPv4Address(d["ip"])))
  return found


def _close_master():
  """Drop the shared SSH connection so the next command uses the new host/key."""
  if not CONFIG.host:
    return
  try:
    subprocess.run(_ssh_opts(CONFIG.host)[:-1] + ["-O", "exit", CONFIG.host], capture_output=True, timeout=5)
  except Exception:
    pass


def check_key(path: str) -> dict:
  """Validate a private key file the user wants to use (empty = default ~/.ssh keys)."""
  path = (path or "").strip()
  if not path:
    return {"ok": True, "path": "", "note": "Using your normal SSH keys from ~/.ssh"}
  f = Path(path).expanduser()
  if not f.is_file():
    return {"ok": False, "path": path, "error": f"no file at {f}"}
  if f.suffix == ".pub":
    return {"ok": False, "path": path, "error": "that's the public key (.pub); choose the private key file next to it"}
  try:
    head = f.read_text(errors="replace")[:200]
  except OSError as e:
    return {"ok": False, "path": path, "error": f"can't read it: {e.strerror}"}
  if "PRIVATE KEY" not in head:
    return {"ok": False, "path": path, "error": "that doesn't look like an SSH private key"}
  if f.stat().st_mode & 0o077:
    return {"ok": False, "path": path, "error": f"ssh refuses keys others can read; run: chmod 600 {f}"}
  pub = f.with_name(f.name + ".pub")
  info = {"ok": True, "path": str(f)}
  if pub.exists():
    p = subprocess.run(["ssh-keygen", "-lf", str(pub)], capture_output=True, text=True)
    info["fingerprint"] = (p.stdout.split() or ["", "?"])[1] if p.returncode == 0 else None
  needs_pass = subprocess.run(["ssh-keygen", "-y", "-P", "", "-f", str(f)], capture_output=True).returncode != 0
  if needs_pass:
    info["warning"] = "this key has a passphrase; it only works if it's loaded in your SSH agent (ssh-add)"
  return info


def set_ssh_key(path: str) -> dict:
  info = check_key(path)
  if not info["ok"]:
    raise ValueError(info["error"])
  _close_master()
  CONFIG.ssh_key = info["path"]
  CONFIG.save_value("ssh_key", info["path"])
  return info
