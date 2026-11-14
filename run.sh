#!/usr/bin/env bash
# Start Comma Clips. Options can also be set in ~/.config/comma-clips/config.json
cd "$(dirname "$0")" && exec python3 -u -m comma_clips "$@"
