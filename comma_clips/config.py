import json
import os
import secrets
from dataclasses import dataclass, field
from pathlib import Path

CONFIG_FILE = Path(os.environ.get("COMMA_CLIPS_CONFIG", Path.home() / ".config" / "comma-clips" / "config.json"))

DEFAULTS = {
  "host": "",                             # ssh destination of the comma, e.g. comma@192.168.1.50 (set in the app)
  "ssh_key": "",                          # private key file for the comma; empty = your normal ~/.ssh keys
  "realdata": "/data/media/0/realdata",   # where the device keeps drives
  "openpilot": "/data/openpilot",
  "pythonpath": "/data/pythonpath",
  "python": "/usr/local/venv/bin/python3",
  "listen": "0.0.0.0",
  "port": 8088,
  "cache_dir": str(Path.home() / ".cache" / "comma-clips"),
  "export_dir": str(Path.home() / "Videos" / "comma-clips"),
  "cache_max_gb": 20,
  "token": "",                            # access key for phones on your network; generated on first run
}


def _write_private(path: Path, text: str):
  """config.json holds the access key, so keep it readable by you only."""
  fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
  with os.fdopen(fd, "w") as f:
    f.write(text)
  os.chmod(path, 0o600)


@dataclass
class Config:
  host: str = DEFAULTS["host"]
  ssh_key: str = DEFAULTS["ssh_key"]
  realdata: str = DEFAULTS["realdata"]
  openpilot: str = DEFAULTS["openpilot"]
  pythonpath: str = DEFAULTS["pythonpath"]
  python: str = DEFAULTS["python"]
  listen: str = DEFAULTS["listen"]
  port: int = DEFAULTS["port"]
  cache_dir: Path = field(default_factory=lambda: Path(DEFAULTS["cache_dir"]))
  export_dir: Path = field(default_factory=lambda: Path(DEFAULTS["export_dir"]))
  cache_max_gb: float = DEFAULTS["cache_max_gb"]
  token: str = ""

  def save_value(self, key: str, value):
    """Persist one setting to config.json (keeps everything else in the file)."""
    saved = json.loads(CONFIG_FILE.read_text()) if CONFIG_FILE.exists() else dict(DEFAULTS)
    saved[key] = value
    CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG_FILE.with_suffix(".tmp")
    _write_private(tmp, json.dumps(saved, indent=2))
    os.replace(tmp, CONFIG_FILE)

  @classmethod
  def load(cls) -> "Config":
    data = dict(DEFAULTS)
    if CONFIG_FILE.exists():
      data.update(json.loads(CONFIG_FILE.read_text()))
    for k in DEFAULTS:
      env = os.environ.get("COMMA_CLIPS_" + k.upper())
      if env:
        data[k] = type(DEFAULTS[k])(env)
    if not data.get("token"):
      data["token"] = secrets.token_urlsafe(9)
      CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
      saved = json.loads(CONFIG_FILE.read_text()) if CONFIG_FILE.exists() else dict(DEFAULTS)
      saved["token"] = data["token"]
      _write_private(CONFIG_FILE, json.dumps(saved, indent=2))
    c = cls(**{k: data[k] for k in DEFAULTS})
    c.cache_dir = Path(c.cache_dir).expanduser()
    c.export_dir = Path(c.export_dir).expanduser()
    c.cache_dir.mkdir(parents=True, exist_ok=True)
    c.export_dir.mkdir(parents=True, exist_ok=True)
    return c


CONFIG = Config.load()
