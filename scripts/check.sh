#!/usr/bin/env bash
# The checks CI runs, with a short summary instead of Gradle's output.
#
#   scripts/check.sh          core tests, then app tests + debug build + lint
#   scripts/check.sh core     core tests only (fast, no Android SDK)
#   scripts/check.sh app      app tests + debug build + lint only
#
# Prints test counts, compile errors, failing tests, lint errors and the lint warnings other than the two
# known UseTomlInstead ones. Exit status is non-zero if anything failed. Full logs: build/check-*.log.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mode=${1:-all}
mkdir -p "$ROOT/build"
status=0

summarise() { # <title> [glob]
  if [ -n "${2:-}" ]; then python3 "$ROOT/.github/scripts/junit_summary.py" --title="$1" "$2"; else python3 "$ROOT/.github/scripts/junit_summary.py"; fi \
    | grep -v '^$'
}

if [ "$mode" = all ] || [ "$mode" = core ]; then
  gradle_retry "$ROOT/build/check-core.log" -q -p core test || status=1
  grep -E "^e: " "$ROOT/build/check-core.log" | head -20
  summarise "Core tests"
fi

if [ "$mode" = all ] || [ "$mode" = app ]; then
  gradle_retry "$ROOT/build/check-app.log" -q :app:testDebugUnitTest :app:assembleDebug :app:lintDebug || status=1
  grep -E "^e: " "$ROOT/build/check-app.log" | head -20
  summarise "App unit tests" "$ROOT/app/build/test-results/testDebugUnitTest/*.xml"
  lint="$ROOT/app/build/reports/lint-results-debug.txt"
  if [ -f "$lint" ]; then
    grep -E "^[0-9]+ errors?, [0-9]+ warnings?" "$lint"
    # Anything but the two known warnings (the included core/ modules have no version to put in the catalog).
    other=$(grep -E "Warning:|Error:" "$lint" | grep -v UseTomlInstead || true)
    [ -n "$other" ] && { echo "Lint, other than the known UseTomlInstead:"; echo "$other"; }
    grep -qE "^0 errors" "$lint" || status=1
  fi
fi

[ $status -eq 0 ] && echo "OK" || echo "FAILED (logs in build/check-*.log)"
exit $status
