#!/usr/bin/env bash
# Desktop shortcut entry point: starts Comma Clips in this terminal and opens it in the browser.
# Close this window (or press Ctrl-C) to stop it.
cd "$(dirname "$0")"
PORT=$(python3 -c 'import json,pathlib; p=pathlib.Path.home()/".config/comma-clips/config.json"; print(json.loads(p.read_text()).get("port",8088) if p.exists() else 8088)')
URL="http://localhost:${PORT}/"

if curl -s -o /dev/null --max-time 2 "${URL}api/status"; then
  echo "Comma Clips is already running."
  echo "  this computer: ${URL}"
  echo "It was started from another window; close that one to stop it."
  xdg-open "$URL" >/dev/null 2>&1 &
  read -r -p "Press Enter to close this window." _
  exit 0
fi

# open the browser once the server answers
( for _ in $(seq 1 40); do
    if curl -s -o /dev/null --max-time 1 "${URL}api/status"; then xdg-open "$URL" >/dev/null 2>&1; break; fi
    sleep 0.5
  done ) &

echo "Close this window or press Ctrl-C to stop Comma Clips."
echo
./run.sh
status=$?
if [ $status -ne 0 ] && [ $status -ne 130 ]; then
  echo
  read -r -p "Comma Clips stopped with an error (exit $status). Press Enter to close." _
fi
