#!/usr/bin/env bash
# Creates the shared Klang signing key (docs/signing.md) in a NEW directory outside the repository.
#
#   scripts/make-signing-key.sh ~/klang-signing
#
# Writes the keystore and one file per secret value. Nothing is printed that is secret, and nothing is
# written inside the repository. Keep the directory safe (password manager or encrypted backup): if the key
# is lost, installed builds can no longer be upgraded, only reinstalled.
set -euo pipefail

dir=${1:?usage: make-signing-key.sh <new directory outside the repository>}
[ ! -e "$dir" ] || { echo "$dir already exists; pick a new directory so nothing is overwritten" >&2; exit 1; }
command -v keytool >/dev/null || { echo "keytool not found: install a JDK (21)" >&2; exit 1; }
case "$(cd "$(dirname "$dir")" && pwd)/$(basename "$dir")" in
  "$(git rev-parse --show-toplevel 2>/dev/null || echo /nonexistent)"/*) echo "choose a directory outside the repository" >&2; exit 1 ;;
esac

umask 077
mkdir -p "$dir/secrets"
alias=klang
# PKCS12 uses one password for store and key. 32 random bytes as base64 without padding or symbols that need quoting.
password=$(head -c 32 /dev/urandom | base64 | tr -d '=+/\n')

keytool -genkeypair -noprompt \
  -keystore "$dir/klang-shared.jks" -storetype PKCS12 \
  -alias "$alias" -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Klang, O=Klang" \
  -storepass "$password" -keypass "$password" >/dev/null 2>&1

base64 < "$dir/klang-shared.jks" | tr -d '\n' > "$dir/secrets/KLANG_KEYSTORE_BASE64"
printf '%s' "$password" > "$dir/secrets/KLANG_KEYSTORE_PASSWORD"
printf '%s' "$password" > "$dir/secrets/KLANG_KEY_PASSWORD"
printf '%s' "$alias" > "$dir/secrets/KLANG_KEY_ALIAS"

cat <<MSG
Created $dir
  klang-shared.jks   the keystore (back this up)
  secrets/           one file per value

Certificate fingerprint (public, safe to share; compare it with the CI summary):
$(keytool -list -v -keystore "$dir/klang-shared.jks" -storepass "$password" 2>/dev/null | grep 'SHA256:' | sed 's/^ *//')

Add the four values as GitHub Actions secrets (needs the gh CLI, run inside a clone of the repository):
  for n in KLANG_KEYSTORE_BASE64 KLANG_KEYSTORE_PASSWORD KLANG_KEY_ALIAS KLANG_KEY_PASSWORD; do
    gh secret set "\$n" < "$dir/secrets/\$n"
  done
or paste each file's content under Settings > Secrets and variables > Actions.

For builds in the cloud sandbox, add the same four names as environment variables in the environment's
settings (cloud environment menu > Edit). To build on this machine:
  export KLANG_KEYSTORE_BASE64=\$(cat "$dir/secrets/KLANG_KEYSTORE_BASE64") \\
         KLANG_KEYSTORE_PASSWORD=\$(cat "$dir/secrets/KLANG_KEYSTORE_PASSWORD") \\
         KLANG_KEY_ALIAS=klang KLANG_KEY_PASSWORD=\$(cat "$dir/secrets/KLANG_KEY_PASSWORD")
MSG
