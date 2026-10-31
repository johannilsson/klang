# Klang: notes for Claude

Android app (Kotlin, Jetpack Compose) that compares reverberation (T20 per octave band, from handclaps on
the phone microphone) before and after a change to a room. Read `README.md` for the product, `docs/dsp.md`
for the method and every threshold, `docs/export-format.md` for the export schema and
`docs/validation-plan.md` for what is and is not validated.

## Layout

- `core/`: **separate Gradle build**, pure Kotlin/JVM, no Android SDK needed. `dsp` (analysis), `sessions`
  (model, JSON store, comparison, export), `cli` (`klang-analyse`).
- `app/`: the Android app. It consumes `core` through `includeBuild("core")` in the root `settings.gradle.kts`.
- `scripts/analyse_export.py`: summarise an export ZIP and re-analyse its WAVs with the current analyser.
- `gradle/libs.versions.toml`: all versions. Use the wrapper (`./gradlew`), never a system Gradle.

## Build and test

```sh
# Core: fast, no Android SDK. Run this after any change in core/.
./gradlew -p core test
./gradlew -p core :cli:installDist          # builds core/cli/build/install/klang-analyse/bin/klang-analyse

# Android (needs the SDK, see below)
./gradlew :app:assembleDebug :app:lintDebug  # APK: app/build/outputs/apk/debug/app-debug.apk

# Regenerate docs/validation-synthetic.md from code (do this after any change to the DSP)
./gradlew -p core :dsp:test --tests '*ValidationReportTest' -Dklang.writeReport=true --rerun-tasks
```

Test results are XML under `core/*/build/test-results/test/`. Gradle only prints failures in `-q` mode, so
after a run count tests and failures from those files. A run filtered with `--tests` replaces the XML for
that module, so the counts only cover the filtered tests: run `./gradlew -p core test` unfiltered for the
real total (47 tests at the time of writing).

Run the app on a device: install the debug APK (sideload). An emulator does not reproduce the microphone
behaviour that matters here (source, effects, headroom): that needs a real phone. CI
(`.github/workflows/build.yml`) builds the core, runs the tests and builds the debug APK.

## Environment gotchas (cloud sandbox)

- **Network**: `dl.google.com` must be in the environment's allowed domains, otherwise AGP and the Android
  SDK cannot be downloaded (HTTP 403 on CONNECT; `curl $HTTPS_PROXY/__agentproxy/status` shows the denied host).
  Do not work around a denied host: tell the user.
- **Android SDK** is not preinstalled. Install the command-line tools from `dl.google.com`
  (`repository2-3.xml` lists `commandlinetools-linux-*_latest.zip`), then
  `sdkmanager --sdk_root=$HOME/android-sdk "platforms;android-36" "build-tools;36.0.0" "platform-tools"`
  and write `sdk.dir=$HOME/android-sdk` to `local.properties` (gitignored).
- **Maven Central rate-limits with HTTP 429** from this sandbox, often on the first build after a change in
  dependencies. It is transient: retry with backoff (15 s, 30 s, 45 s…), e.g. loop the Gradle command while
  its output contains `429`. A 429 is not a build failure in the code.
- JDK in the sandbox is 21; the code targets Java 17 bytecode (`jvmTarget` 17) so it also builds on JDK 17 in CI.
- Unzip user-provided exports into a **new** directory and never `rm -rf` (the safety check blocks it, and it
  is not needed).

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

## Conventions

- Thresholds live in `AnalysisConfiguration`; do not scatter constants through the analyser.
- After changing the DSP: run `./gradlew -p core test`, regenerate `docs/validation-synthetic.md`, update
  `docs/dsp.md` and the version.
- New analysis behaviour gets a test with a synthetic fixture (`SyntheticImpulse`) for both the case it should
  catch and the case it must not catch.
- Prefer small commits with a message that says why. Open pull requests only when asked.
