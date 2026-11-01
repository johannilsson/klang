# Klang: notes for Claude

Android app (Kotlin, Jetpack Compose) that compares reverberation (T20 per octave band, from handclaps on
the phone microphone) before and after a change to a room. Read `README.md` first (the product, short). Open the
others **only when the task touches them**, not up front:

| When you change… | Read |
|---|---|
| the DSP, a threshold, the analysis | `docs/dsp.md` (every threshold), `docs/validation-plan.md` |
| the export or import | `docs/export-format.md` |
| targets, reference profiles, BB93 | `docs/reference-profiles.md` |
| summaries, hints, Sabine | `docs/summaries.md` |
| the attempt screen, verdicts, the recording graph | `docs/attempt-report.md` |
| signing, APKs | `docs/signing.md` |
| colours / the visual style | `docs/design-tokens.md` (edit `design/tokens.json`, run `scripts/gen_tokens.py`; never edit `Color.kt` by hand) |

## Layout

- `core/`: **separate Gradle build**, pure Kotlin/JVM, no Android SDK needed. `dsp` (analysis), `sessions`
  (model, JSON store, comparison, export), `cli` (`klang-analyse`).
- `app/`: the Android app. It consumes `core` through `includeBuild("core")` in the root `settings.gradle.kts`.
- `scripts/analyse_export.py`: summarise an export ZIP and re-analyse its WAVs with the current analyser.
- `gradle/libs.versions.toml`: all versions. Use the wrapper (`./gradlew`), never a system Gradle.

## Scripts and skills (use these instead of typing Gradle by hand)

```sh
scripts/check.sh [core|app]   # the CI checks; prints counts, errors and failing tests only (retries Maven 429)
scripts/build-apk.sh          # builds the debug APK, shows version and signing key (--bump raises versionCode)
scripts/screens.sh            # renders the main screens to build/screens/*.png (real analyser, synthetic recordings)
```

Project skills in `.claude/skills/`: `build-apk`, `render-screens`, `squash-pr`. Add a scene to
`app/src/test/.../ui/ScreenshotsTest.kt` rather than writing a new throwaway render test. Keep tool output small:
count with the scripts, look at one cropped image, read a log only for its first `e:` line.

## Build and test

```sh
# Core: fast, no Android SDK. Run this after any change in core/.
./gradlew -p core test
./gradlew -p core :cli:installDist          # builds core/cli/build/install/klang-analyse/bin/klang-analyse

# Android (needs the SDK, see below)
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug  # APK: app/build/outputs/apk/debug/app-debug.apk

# Regenerate docs/validation-synthetic.md from code (do this after any change to the DSP)
./gradlew -p core :dsp:test --tests '*ValidationReportTest' -Dklang.writeReport=true --rerun-tasks
```

Test results are XML under `core/*/build/test-results/test/` and `app/build/test-results/testDebugUnitTest/`.
Gradle only prints failures in `-q` mode, so after a run count tests and failures from those files:
`python3 .github/scripts/junit_summary.py` (core) and
`python3 .github/scripts/junit_summary.py --title='App unit tests' 'app/build/test-results/testDebugUnitTest/*.xml'`.
A run filtered with `--tests` replaces the XML for that module, so the counts only cover the filtered tests:
run unfiltered for the real total. Do not write test counts into docs; CI's job summary shows the current ones.

Run the app on a device: install the debug APK (sideload). An emulator does not reproduce the microphone
behaviour that matters here (source, effects, headroom): that needs a real phone. CI
(`.github/workflows/build.yml`) runs the core and app unit tests, lint, and builds the debug APK.

## Environment gotchas (cloud sandbox)

- **Network**: `dl.google.com` must be in the environment's allowed domains, otherwise AGP and the Android
  SDK cannot be downloaded (HTTP 403 on CONNECT; `curl $HTTPS_PROXY/__agentproxy/status` shows the denied host).
  Do not work around a denied host: tell the user.
- **Android SDK** is not preinstalled. Install the command-line tools from `dl.google.com`
  (`repository2-3.xml` lists `commandlinetools-linux-*_latest.zip`), then
  `sdkmanager --sdk_root=$HOME/android-sdk --licenses`, then
  `sdkmanager --sdk_root=$HOME/android-sdk "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"`
  (compileSdk 37; AGP downloads any other build-tools it needs) and write `sdk.dir=<absolute $HOME/android-sdk>`
  to `local.properties` (gitignored; in each git worktree too).
- **Maven Central rate-limits with HTTP 429** from this sandbox (both `repo.maven.apache.org` and
  `repo1.maven.org`). The builds therefore resolve from Google's public mirror of Maven Central first
  (`maven-central.storage-download.googleapis.com`, in both `settings.gradle.kts` files, before the plugin
  portal, which redirects to Maven Central), with `mavenCentral()` as the fallback; Robolectric's android-all
  download uses the same mirror (`robolectric.dependency.repo.url` in `app/build.gradle.kts`). A 429 is not a
  build failure in the code: if one still appears (an artifact the mirror lacks), retry with backoff
  (15 s, 30 s, 45 s…). Keep any new repository behind the mirror.
- The build runs on **JDK 21** (LTS), in the sandbox and in CI. The code is still compiled to Java 17 bytecode
  (`jvmTarget` 17), so the app and `klang-analyse` run on Java 17+. JDK 21 is a requirement, not a preference:
  Robolectric's Android SDK 36 runtime (app unit tests) refuses to start on older Java. Do not move CI to a
  non-LTS JDK, and try a newer LTS locally before changing CI.
- **Signing**: builds are signed with a shared key from the environment variables `KLANG_KEYSTORE_BASE64`,
  `KLANG_KEYSTORE_PASSWORD`, `KLANG_KEY_ALIAS`, `KLANG_KEY_PASSWORD` (docs/signing.md), so the APK can upgrade
  the one on the user's phone. Without them the build uses the default debug key, which cannot upgrade a build
  signed with another key: say so when you hand over an APK, and check it with `apksigner verify --print-certs`.
  Never create or print the real key, and never commit one. A debug build does not need a new `versionCode`
  (the same code installs over the installed app); bump it only when the user asks.
- Unzip user-provided exports into a **new** directory and never `rm -rf` (the safety check blocks it, and it
  is not needed).
- **Parallel agents in git worktrees** may start from an old commit, not from your branch. Tell each agent to
  run `git checkout -b <its-branch> <your-branch>` first and check `git log -1`, then merge their branches one at
  a time and run the tests after each merge. Each worktree also needs its own `local.properties`.
- **WebFetch and the shell use different network paths**: a host added to the allowed domains can still fail in
  WebFetch (DNS error) while `curl` in the shell reaches it. Download public reference documents with `curl` into
  a new scratch directory and read them there (`pdftotext -layout` is installed).

## Analysing a user's export or WAV

Users send `klang-export-*.zip` (from the app's share sheet) and `klang-test-*.wav` (from the Microphone check).

```sh
./gradlew -p core :cli:installDist
scripts/analyse_export.py /path/export.zip /new/empty/dir   # stored results + fresh re-analysis of every clap
core/cli/build/install/klang-analyse/bin/klang-analyse file.wav            # table (add --json, --reversed)
```

Compare "as stored" with "re-analysed": an old app build may have analysed with an older algorithm version.
Look at a 100–250 ms RMS timeline of a failing recording before blaming the algorithm: most failures so far
were the recording (taps in the pre-roll, no headroom, ambient noise, late movement), not the DSP.

## Rules that must not be broken

- **Do not relax quality gates to make numbers appear.** If clap measurements cannot support T20, say so and
  propose controlled speaker excitation. Loosening a gate needs evidence (a test or real recordings) and a
  note in `docs/dsp.md` saying why. A principled change is fine (example: a second sound event only matters
  inside a band's analysed decay, because the Schroeder integral is truncated there).
- **Any behaviour change in `core/dsp` bumps `AnalysisConfiguration.VERSION`**, adds the new field to
  `Records.configuration` in `core/sessions` and is documented in `docs/dsp.md`. Results store the algorithm
  version and configuration; re-analysis appends a result and never replaces a recording or an older result.
- **Levels are dBFS, never SPL.** Do not label anything dB SPL. Calibrated SPL is out of scope.
- **Never call a T20 anything but the −5…−25 dB fit extrapolated to 60 dB.** EDT stays secondary and labelled
  as not validated. Never substitute EDT or a shorter range for T20.
- **No statistical-confidence claims** (no confidence percentages, no "significant" from medians or
  non-overlapping error bars). The comparison is a descriptive rule; with five claps say "Change is
  inconclusive" when variability is not exceeded.
- Invalid bands show reasons, not numbers. Questionable bands keep their value and flags.
- Microphone use only inside an explicit measurement, test capture or practice flow, in the foreground. The
  capture loop only copies samples into a preallocated array; no DSP, storage or UI work on it.
- Example values may appear in UI previews only, visibly marked as examples.
- Keep the synthetic tests as engineering checks. Do not present their accuracy as real-room accuracy.
- **Reference data only from public, freely readable sources.** Target values, guide ranges, formulas and
  coefficients the app ships must come from documents anyone can read for free (e.g. UK Building
  Bulletin 93, Open Government Licence; public-domain physics such as Sabine's formula), cited in `docs/`
  with title, edition, table/section and URL, and verified against the source, never from memory.
  **Closed sources** (paid standards such as DIN, SS, ISO, or ANSI unless its publisher offers it free) may
  inform the data model's structure (e.g. targets that depend on volume, per-band tolerances, room-use
  groups), never the values in the repo or the app. Other data comes in only as user-entered or imported
  reference profiles (#27), labelled as such.

## Conventions

- Thresholds live in `AnalysisConfiguration`; do not scatter constants through the analyser.
- After changing the DSP: run `./gradlew -p core test`, regenerate `docs/validation-synthetic.md`, update
  `docs/dsp.md` and the version.
- New analysis behaviour gets a test with a synthetic fixture (`SyntheticImpulse`) for both the case it should
  catch and the case it must not catch.
- Prefer small commits with a message that says why. Open pull requests only when asked.
