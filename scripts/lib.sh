# Shared helpers for the scripts in this folder. Source it, do not run it.

# Repository root, whatever the current directory is.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# gradle_retry <log-file> <gradle args...>
# Runs ./gradlew with its output in <log-file> and returns Gradle's exit code. Maven Central can answer a shared
# network with HTTP 429 (the builds use a mirror first, see CLAUDE.md); that is not a failure of the code, so
# retry with growing pauses while the log says so.
gradle_retry() {
  local log=$1; shift
  local i rc
  for i in 1 2 3 4 5 6; do
    (cd "$ROOT" && ./gradlew "$@") >"$log" 2>&1; rc=$?
    if grep -q "status code 429\|HTTP 429\|HTTP response code: 429" "$log"; then
      echo "Maven answered 429, retry $i in $((i * 15)) s" >&2
      sleep $((i * 15)); continue
    fi
    return $rc
  done
  return 1
}

# android_tool <name>: path of a build-tools binary (apksigner, aapt2) from the SDK, or empty.
android_tool() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -z "$sdk" ] && [ -f "$ROOT/local.properties" ] && sdk="$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" | head -1)"
  [ -n "$sdk" ] && ls "$sdk"/build-tools/*/"$1" 2>/dev/null | sort -V | tail -1
}
