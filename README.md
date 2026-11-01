# Klang: room acoustics before/after

An Android app for repeatable before/after experiments in a domestic room. It answers one question:
did adding a panel, curtains or a rug reduce reverberation around the dining table by **more than
normal measurement variability**?

It uses handclaps recorded on the phone's built-in microphone, and reports position-specific T20
decay estimates per octave band, with explicit validity checks. It is a comparative tool. It is not
a calibrated sound level meter and not a standards-compliant acoustic survey. Levels are shown in
dBFS (relative to digital full scale), never dB SPL. When a result would be unreliable, the app
shows the reason instead of a number.

## Status (0.1.0)

| Part | State |
|---|---|
| DSP module (`core/dsp`) | Implemented; 31 unit tests on synthetic fixtures (see [validation-synthetic.md](docs/validation-synthetic.md)) |
| Sessions, persistence, comparison, export (`core/sessions`) | Implemented; 8 unit tests |
| Desktop analyser for imported WAVs (`core/cli`) | Implemented, for comparison with REW |
| Android capture layer and capability screen (`app`) | Implemented; builds and passes lint; **not yet run on a device** |
| Guided sessions, attempt inspection, comparison UI, export/share (`app`) | Implemented; builds and passes lint; **not yet run on a device** |
| REW comparison, device validation, unchanged-room repeatability | Not started; see [validation-plan.md](docs/validation-plan.md) |

The app module started from the Android Studio *Empty Activity* (Jetpack Compose) template:
version catalog, single activity, Material 3 theme. It builds with AGP 8.13.2, Kotlin 2.4.21 and
Compose BOM 2025.10.00 (`./gradlew :app:assembleDebug`, lint clean of errors); CI
(`.github/workflows/build.yml`) builds it on every push. Newer AndroidX releases and AGP 9 exist;
upgrade with Android Studio's assistant when convenient.

Following the brief's delivery order, device feasibility and unchanged-room repeatability (steps
2–3) should be checked on the target phone before relying on the session and comparison UI.

## Layout

```
app/                Android app (Kotlin, Jetpack Compose, minSdk 29)
  audio/            AudioRecord capture, source/format selection, effects, route and config monitoring
  ui/screens/       Home, Microphone check, Room, Session, Attempt, Compare
core/               Separate Gradle build, pure Kotlin/JVM — no Android SDK needed
  dsp/              Octave filters, impulse detection, Schroeder/truncation, T20/EDT fits, quality gates
  sessions/         Room/Position/Session/Attempt model, JSON file store, re-analysis, comparison, export
  cli/              klang-analyse: analyse WAV files on a desktop
docs/               DSP method, export schema, validation plan, generated synthetic results
scripts/            analyse_export.py: summarise an export ZIP and re-analyse its recordings
CLAUDE.md           notes for Claude: build/test/run steps, sandbox gotchas, rules that must not be broken
```

## Build

```sh
# Core: DSP + sessions + CLI (any JDK 17+, no Android SDK)
./gradlew -p core test
./gradlew -p core :cli:installDist
core/cli/build/install/klang-analyse/bin/klang-analyse recording.wav        # table
core/cli/build/install/klang-analyse/bin/klang-analyse --json recording.wav # full result
core/cli/build/install/klang-analyse/bin/klang-analyse --synth test.wav --t60 0.6

# Regenerate docs/validation-synthetic.md from code
./gradlew -p core :dsp:test --tests '*ValidationReportTest' -Dklang.writeReport=true --rerun-tasks

# Android (needs the Android SDK; open the root folder in Android Studio, or:)
./gradlew :app:assembleDebug
```

## Using the app

1. **Microphone check**. Shows what the phone claims: UNPROCESSED support, formats, AGC/NS/AEC,
   inputs. A test capture runs the full analysis without saving it, and *Share WAV* sends the file
   out, e.g. to REW.
2. **Room → positions**, set up once per room. Three positions around the table at seated ear height,
   each with the phone placement and its own fixed clap spot (distance and direction from the phone).
   Every session records at the room's positions.
3. **Sessions**. *Baseline*, then a *Repeat baseline* with the room unchanged, then *Treatment* with
   the intervention described; *New session* suggests the next type in that order. Each session stores
   its conditions (doors and windows, furnishings, occupancy, appliances), prefilled from the previous
   session. Forms open as sheets from buttons; secondary actions (protocol, compare, export,
   microphone check, how to clap) are in the top-bar menu.
4. **Per position**: record background, then claps. Each recording starts with a 3 s "get ready"
   countdown with the microphone off (so the button tap is not recorded), then lasts 5 s: stay quiet
   → *Clap once now* → stay still. A live level meter shows how loud the clap is (aim for −12 to
   −2 dBFS: louder risks the microphone compressing the clap, quieter risks too little margin). The
   phone buzzes when recording ends: stay still until then. Each session screen has a "How to clap"
   card (spot, strength, distance) and a **Practice clap level** mode that listens for 30 s, reports
   each clap's peak with plain feedback (too loud / good / quiet) and saves nothing, to find your
   distance before recording. The suggested 1.5–2 m start distance is not validated: use the meter. Every attempt is saved and analysed straight away. Rejected attempts are
   kept, with their reasons and advice (never "clap harder"), and can be deleted.
5. **Compare** shows matched positions band by band: every usable attempt, medians and IQRs,
   absolute and percentage change, valid/attempted counts, configuration warnings, and a verdict.
   Without a repeat baseline, or when the configurations differ, the verdict is *Change is
   inconclusive*.
6. **Export**: a ZIP with `results.json`, `summary.csv`, `comparison.csv` and the original WAVs,
   shared through the system share sheet ([schema](docs/export-format.md)).

## Design notes

* **Capture**: `AudioRecord`, mono, 48 kHz PCM float preferred (then PCM16, then 44.1 kHz). The
  actual format is recorded. The UNPROCESSED source is used only when
  `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` is true; otherwise VOICE_RECOGNITION, and the fallback
  is flagged in every result. Controllable AGC, NS and AEC are disabled and their state is
  recorded. The built-in mic is preferred and the routed device recorded. Route changes, system
  silencing, short reads and suspected overruns become capture events that invalidate the attempt.
  The capture loop runs on an urgent-audio thread and only copies samples into a preallocated
  array; analysis and storage happen afterwards. Leaving the app discards a running capture.
* **Analysis**: [docs/dsp.md](docs/dsp.md) documents every step and threshold: the filter design,
  the margin definition, truncation with tail compensation (no noise subtraction), T20 fitted over
  −5…−25 dB and extrapolated to 60 dB, EDT as a secondary result, and the quality gates.
* **Persistence**: app-private JSON plus the original WAVs, written atomically. Every analysis is
  appended with its algorithm version and full configuration, so re-analysis never replaces a
  recording or an earlier result.
* **Comparison**: a descriptive, versioned rule, not a significance test. There are no confidence
  percentages and no combined room score.

## Later extensions (not implemented)

USB microphones and per-device profiles; interrupted pink noise or swept sines from a separate
speaker (the DSP takes any impulse-like recording, so a deconvolved sweep IR can use the same
pipeline); C50 from validated impulse responses; calibrated SPL as a separate capability. The phone's
own speaker is deliberately not used as a source.
