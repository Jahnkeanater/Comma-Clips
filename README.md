# Comma Clips

A locally hosted web UI to access and export clips from your comma device.

Browse the drives stored on your comma device and export full-resolution clips, from your Linux desktop or your phone. It works like a local version of comma connect: no cloud and no upload, straight from the device over your own Wi-Fi.

- **Drives list**: every drive on the device, grouped by day, with thumbnails.
- **Preview player**: the whole drive as one scrubbable video, using the device's small preview files.
- **Range picker**: tap the timeline to seek, drag its edges, use "Set start/end here", nudge ±1 s/±5 s, or use the quick presets.
- **Export**: a single MP4 from the full-resolution road, wide or driver camera, joined across the device's 1-minute segments.
  - *Exact cut* re-encodes to H.264 so the clip plays anywhere.
  - *Fast copy* keeps the original HEVC (no quality loss, very quick) and starts at the nearest keyframe.
- **Telemetry overlay** (toggle per export, and each element can be switched on or off):
  - top left: speed with a gas (green) / brake (red) dot, engaged status, clock, GPS position, heading and altitude
  - top centre: turn signals, flashing while on
  - top right: lead car speed and distance, acceleration and steering angle
  - below that: a g-force dot and comma's steering wheel icon, turning with the real wheel
  - GPS position is off by default, since it shows exactly where you were
- **Telemetry preview**: the same readout live on the preview player.
- **Connection screen**:
  - search your network for the comma, or enter its IP or `comma-xxxxxxxx.local` name
  - pick which SSH key to use

There's also a standalone **Android app** that needs no computer; see [android/README.md](android/README.md).

It was built and tested on a comma 4 running sunnypilot. Anything based on openpilot that keeps drives in `/data/media/0/realdata` should work.

Not affiliated with comma.ai.

## How it works

Comma Clips runs on your desktop and reaches the comma over SSH. It **only reads** from the device:

- Video files are copied with `ssh … cat` into `~/.cache/comma-clips` (capped at 20 GB, oldest removed first).
- Telemetry is read by piping a small script into the comma's own Python over SSH. That script only reads the logs, and nothing is written on the device. It only runs while the comma is parked.
- ffmpeg on the desktop joins, trims and encodes. Exports land in `~/Videos/comma-clips`.

Your phone opens the desktop's web page over Wi-Fi.

## Requirements

- Linux desktop with Python 3.10 or newer (standard library only, no pip packages)
- `ffmpeg` (with libass, which the Ubuntu/Debian/Fedora packages include)
- OpenSSH client (`ssh`, `ssh-keyscan`, `ssh-keygen`)
- A comma device on the same network with SSH turned on

## Setup

### 1. Let your computer log in to the comma

The comma accepts the SSH keys published on a GitHub account.

1. If you don't have a key yet, create one:
   ```bash
   ssh-keygen -t ed25519 -f ~/.ssh/comma_ed25519 -N ""
   ```
2. Add the public half (`~/.ssh/comma_ed25519.pub`) to your GitHub account under **Settings → SSH and GPG keys**.
3. On the comma, turn on **SSH** and enter your **GitHub username**. It's under Developer or Network in the device settings, depending on the software version.

### 2. Install and run

```bash
sudo apt install ffmpeg
```

```bash
git clone https://github.com/Jahnkeanater/Comma-Clips.git
```

```bash
cd Comma-Clips && ./run.sh
```

It prints a link for this computer (`http://localhost:8088/`) and one for your phone (`http://<computer-ip>:8088/?k=<access key>`).

On first run the page opens **Connection**:

1. Use **Search network** (or type the comma's IP or name).
2. Fill in **SSH key** if you made a separate key (e.g. `~/.ssh/comma_ed25519`). Leave it empty to use your usual keys.
3. Choose **Save & connect**.

### Optional: desktop shortcut

```bash
./install-desktop-shortcut.sh
```

This adds **Start Comma Clips** to your desktop and app menu. It opens a terminal running the server, plus the app in your browser. Close the window to stop it.

### Phone

Open the phone link once and the access key is remembered. In Chrome, **⋮ → Add to Home screen / Install app** gives it an icon and a full-screen window.

## Security

- **Access key:** other devices on your network need the access key in the link. This computer doesn't.
- **Settings:** `~/.config/comma-clips/config.json` holds the comma address, SSH key path and access key. It's created readable by you only. Delete the `token` line and restart to get a new access key.
- **Host keys:**
  - New comma host keys are only trusted after you confirm the fingerprint on the Connection screen. A key you already trust under another name or IP is accepted automatically.
  - Trusted keys go in `~/.config/comma-clips/known_hosts`. `~/.ssh/known_hosts` is read but never changed.
- **Local only:** to allow only this computer, set `"listen": "127.0.0.1"`.

## Settings

All in `~/.config/comma-clips/config.json`. You can also override them with environment variables like `COMMA_CLIPS_PORT=9000`.

| key | default | meaning |
|---|---|---|
| `host` | *(set in the app)* | SSH destination, e.g. `comma@192.168.1.50` |
| `ssh_key` | *(empty)* | private key file; empty uses your normal `~/.ssh` keys |
| `port` | `8088` | web port |
| `listen` | `0.0.0.0` | `127.0.0.1` allows only this computer |
| `export_dir` | `~/Videos/comma-clips` | where clips are saved |
| `cache_max_gb` | `20` | local copy of device files |
| `realdata`, `openpilot`, `pythonpath`, `python` | comma defaults | paths on the device, if your fork differs |

## Notes

- The road camera uses about 35–40 MB per minute. Over Wi-Fi, copying takes roughly 10 s per minute of video, and segments already copied are reused.
- The device deletes old drives when its storage fills up. Export anything you want to keep.
- Drive start times in the list are estimated from file times. The overlay clock comes from GPS time in the logs.
- Driver-camera export only works for drives where the device recorded the driver camera.

## License

MIT, see [LICENSE](LICENSE). The steering wheel icon (`comma_clips/assets/chffr_wheel.png`) comes from [openpilot](https://github.com/commaai/openpilot), also MIT; see [NOTICE](comma_clips/assets/NOTICE.md).
