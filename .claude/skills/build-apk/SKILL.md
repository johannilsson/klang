---
name: build-apk
description: Build the debug APK for the user to install on their phone and report its version and signing key. Use when asked to build or send the APK.
---

# Build the APK

1. Run `scripts/build-apk.sh`. It builds, copies the APK to `build/apk/klang-<name>-code<N>-debug.apk` and prints
   the version, the signing certificate and whether it is the default debug key. Do not run Gradle by hand for
   this. Do **not** bump `versionCode`: a debug build installs over the installed app with the same code. Add
   `--bump` only if the user asks for a new version code.
2. If it says the build failed, read `build/build-apk.log` for the first `e:` line; do not paste the log.
3. Nothing to commit unless you used `--bump` (then commit it on its own and push).
4. Send the APK with SendUserFile (`display: attach`), with a caption that has the versionCode and the signing
   state from step 1.
5. Tell the user, in two or three lines, what is in it and, if the script said **default debug key**, that it
   will not upgrade a build signed with another key (uninstall first, export rooms before) and that
   `docs/signing.md` explains the shared key. Never create or print the real signing key.
