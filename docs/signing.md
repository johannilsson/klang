# Signing: one key for every build

Android installs an update over an installed app only if both are signed with the **same key**. The
default debug key is generated per machine, so a build from CI and one from another machine (or from the
cloud sandbox) cannot upgrade each other: the install fails with "App not installed" or
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way out is to uninstall, which deletes the app's data.
Klang therefore signs its debug (the sideloaded one) and release builds with one shared key, kept out of
the repository.

## How the build finds the key

`app/build.gradle.kts` reads four environment variables:

| Variable | Content |
|---|---|
| `KLANG_KEYSTORE_BASE64` | the keystore file (`klang-shared.jks`, PKCS12), base64 on one line |
| `KLANG_KEYSTORE_PASSWORD` | the keystore password |
| `KLANG_KEY_ALIAS` | the key alias (`klang`) |
| `KLANG_KEY_PASSWORD` | the key password (the same as the keystore password for PKCS12) |

* **All four set**: the build signs with the shared key and says so ("Signing with the shared Klang key").
* **None set, or all empty** (pull requests from forks, a fresh checkout): the build falls back to the
  default debug key and says so. Such an APK will not upgrade a build signed with the shared key.
* **Some set**: the build fails and names the missing ones, rather than quietly producing an APK that
  cannot upgrade the installed app.

CI (`.github/workflows/build.yml`) passes the four GitHub Actions secrets of the same names to the build and
prints the signing certificate's SHA-256 in the job summary, so you can see which key signed the uploaded APK.

## One-time setup

1. Create the key **on your own machine** (the script writes outside the repository and prints no secret):

   ```sh
   scripts/make-signing-key.sh ~/klang-signing
   ```

   It prints the certificate fingerprint (public). Back the directory up (password manager or an encrypted
   backup): if the key is lost, installed builds can no longer be upgraded, only reinstalled.
2. Add the four values as GitHub Actions secrets, with the commands the script prints
   (`gh secret set NAME < ~/klang-signing/secrets/NAME`), or under *Settings › Secrets and variables › Actions*.
3. For builds in the cloud sandbox, add the same four names as environment variables in the environment's
   settings (cloud environment menu › Edit). They are not committed anywhere and a new session picks them up.
4. For local builds, export the four variables (the script prints the command).

Never paste the key or its passwords into an issue, a pull request or a chat.

## The first upgrade

An app already installed with a *different* key (any build made before this was set up) cannot be updated by
a build with the shared key. Export your rooms (*Export all*), uninstall once, install the first build signed
with the shared key, and import the export. From then on every build upgrades the previous one, as long as
its `versionCode` is higher (`defaultConfig.versionCode` in `app/build.gradle.kts`).

## Checking which key signed an APK

```sh
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

Compare the SHA-256 digest with the fingerprint `make-signing-key.sh` printed.

## Rotating the key

A new key cannot upgrade builds signed with the old one (Android's key rotation needs the old key to sign a
proof of rotation). Treat it like the first upgrade: export, uninstall, install, import.
