#!/usr/bin/env bash
# Adds a "Comma Clips" launcher to your desktop and app menu (Linux desktops that follow the XDG spec).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ENTRY="[Desktop Entry]
Version=1.0
Type=Application
Name=Start Comma Clips
Comment=Browse comma drives and export clips (close the window to stop)
Exec=\"$HERE/start-desktop.sh\"
Icon=$HERE/comma_clips/static/icon.svg
Terminal=true
Categories=Utility;"
chmod +x "$HERE/start-desktop.sh" "$HERE/run.sh"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$APPS"
printf '%s\n' "$ENTRY" > "$APPS/comma-clips.desktop"
echo "Added to the app menu: $APPS/comma-clips.desktop"
DESK="$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")"
if [ -d "$DESK" ]; then
  printf '%s\n' "$ENTRY" > "$DESK/Start Comma Clips.desktop"
  chmod +x "$DESK/Start Comma Clips.desktop"
  gio set "$DESK/Start Comma Clips.desktop" metadata::trusted true 2>/dev/null || true
  echo "Added to the desktop: $DESK/Start Comma Clips.desktop"
  echo "(If GNOME shows it greyed out, right-click it and choose Allow Launching.)"
fi
