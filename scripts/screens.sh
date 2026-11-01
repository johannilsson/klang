#!/usr/bin/env bash
# Renders the main screens to PNGs from the real analyser on synthetic recordings (no phone needed).
#
#   scripts/screens.sh [out-dir]      default: build/screens
#
# Writes session, attempt-clean / -noisy / -tap / -loud, session-details, room and compare, cropped to their
# content. Look at one or two of them, not all. Dialogs and sheets are not captured (they are separate windows).
# The recordings are synthetic: this shows what the analyser makes of them, not a real room.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
out=$(realpath -m "${1:-$ROOT/build/screens}")
mkdir -p "$out" "$ROOT/build"
export KLANG_SCREENSHOTS="$out"
gradle_retry "$ROOT/build/screens.log" -q :app:testDebugUnitTest --tests '*ScreenshotsTest' || {
  grep -E "^e: |FAILED|Exception" -A3 "$ROOT/build/screens.log" | head -20
  echo "Rendering FAILED (log: build/screens.log)" >&2; exit 1
}
ls -1 "$out"/*.png
