#!/usr/bin/env bash
# Builds the debug APK for sideloading and says what it is.
#
#   scripts/build-apk.sh             bump versionCode by one, build
#   scripts/build-apk.sh --no-bump   build without changing versionCode
#
# An update only installs over an installed app when both have the same signing key and a higher versionCode
# (docs/signing.md), so a bump is the default. The APK is copied to build/apk/. The bump is left in
# app/build.gradle.kts for you to commit.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
gradle_file="$ROOT/app/build.gradle.kts"
mkdir -p "$ROOT/build/apk"

old=$(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' "$gradle_file" | head -1)
[ -n "$old" ] || { echo "Could not find versionCode in app/build.gradle.kts" >&2; exit 2; }
if [ "${1:-}" = "--no-bump" ]; then new=$old; else
  new=$((old + 1))
  sed -i "s/^\( *versionCode = \)$old\b/\1$new/" "$gradle_file"
fi

gradle_retry "$ROOT/build/build-apk.log" :app:assembleDebug || {
  grep -E "^e: |What went wrong" -A3 "$ROOT/build/build-apk.log" | head -20
  echo "Build FAILED (log: build/build-apk.log)" >&2; exit 1
}

apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
name=$(sed -n 's/^ *versionName = "\(.*\)".*/\1/p' "$gradle_file" | head -1)
out="$ROOT/build/apk/klang-$name-code$new-debug.apk"
cp "$apk" "$out"

echo "versionCode $old -> $new, versionName $name"
echo "APK: $out ($(du -h "$out" | cut -f1))"
signer=$(android_tool apksigner)
if [ -n "$signer" ]; then
  certs=$("$signer" verify --print-certs "$apk" 2>&1)
  echo "$certs" | grep -E "certificate DN|SHA-256 digest" | sed 's/^Signer #1 //'
  if echo "$certs" | grep -q "CN=Android Debug"; then
    echo "SIGNED WITH THE DEFAULT DEBUG KEY: it will not upgrade a build signed with another key (docs/signing.md)."
  else
    echo "Signed with the shared key."
  fi
else
  echo "(apksigner not found: set ANDROID_HOME or sdk.dir in local.properties to check the signature)"
fi
[ "$new" != "$old" ] && echo "versionCode changed in app/build.gradle.kts: commit it."
exit 0
