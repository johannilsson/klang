# Validation plan and status

Status as of version 0.1.0. ✅ done · ⏳ not yet done · ⚠ partially done

## 1. DSP on synthetic fixtures ✅

`./gradlew -p core test` runs these on every build. Fixture model: `SyntheticImpulse` (expected T
derived from the generating envelope).

* ✅ Filter: 0 dB at fc, −3 dB at the edges, > 30 dB rejection one octave beyond each edge,
  44.1/48 kHz, forward and time-reversed.
* ✅ Clean deterministic fixtures (damped tones), T60 0.3–1.2 s, all bands, 3 seeds: T20 error < 5 %
  (observed ≤ 0.4 % at 500–4000 Hz and ≤ 2.1 % at 250 Hz). See [validation-synthetic.md](validation-synthetic.md).
* ✅ Decaying-noise fixtures, 10 seeds, T60 0.4 and 0.8 s: mean within 3 % (6 % at 250 Hz), and every
  realisation measurable. Single realisations scatter by several percent, as real impulse responses
  do. At T60 = 0.3 s the 250 Hz mean reads ~9 % high (documented limitation, not a test target).
* ✅ Frequency-dependent decay resolved per band.
* ✅ Gain invariance (×0.1, ×0.5, ×1.8): identical states and T20 within 0.01 %.
* ✅ Added noise: unbiased at ~40 dB margin; at −30 dBFS background every band is withheld with
  `INSUFFICIENT_SNR_MARGIN`.
* ✅ Long noisy tail: truncation keeps T20 within 5 %.
* ✅ Clipping, flat-top limiting, dropouts, capture discontinuities: invalid with reasons.
* ✅ Second clap during the decay, louder second clap, loud event in the background: invalid.
* ✅ Short recordings (no pre-roll, cut-off tail, decay longer than the recording): no T20, with reasons.
* ✅ Double-slope decay: flagged questionable (`CURVED_DECAY` / `NON_LINEAR_DECAY`).
* ✅ EDT is the conventional 60 dB extrapolation of the 0…−10 dB fit.
* ✅ Determinism; results carry the algorithm version and the full configuration.

These are engineering test targets on synthetic data. They are **not** a real-room accuracy claim.

## 2. Comparison with Room EQ Wizard ⏳

Aim: compare the *same input files* with an independent implementation and document the differences.
Agreement on one file does not establish equivalence.

1. On the phone, make test captures (Microphone check → Test capture → Share WAV), or export a
   session (ZIP → `recordings/`).
2. Desktop: `core/cli/build/install/klang-analyse/bin/klang-analyse file.wav`
   (`./gradlew -p core :cli:installDist`; `--json` for machine-readable output; `--reversed` for
   time-reversed filtering).
3. REW: *File → Import → Import impulse response*, then the RT60 graph with octave filters. Record
   for each file and band: T20, EDT, and REW's noise-handling and start-point settings.
4. Write down the expected sources of difference: REW's filter design (and whether it uses
   time-reversed filtering), its impulse start (peak vs threshold), Lundeby/noise compensation vs our
   truncation using the measured background, and the integration limits.
5. Check several files, several positions and all bands, including marginal ones. Report the
   differences per band; do not average them away.

## 3. Device capability on the target phone ⚠ (first findings)

**Google Pixel 9 Pro, Android 17 (API 37), first test capture:**

* `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` = false, so capture uses VOICE_RECOGNITION and every
  result is flagged as a fallback. Whether that source applies hidden processing is **not** yet known.
* 48 kHz mono PCM float opens; the built-in mic is routed; no capture events.
* AGC is not offered. Noise suppression and echo cancellation are offered and were disabled.
* `activeRecordingConfiguration` was unavailable while recording; the capture notes say so.
* The first 839 samples (17 ms) were exact zeros: microphone start-up. The dropout check wrongly
  rejected this until algorithm 0.1.1.
* The first clap peaked at 0.995 (−0.05 dBFS): almost no headroom. 0.1.1 now warns
  (`NEAR_FULL_SCALE`); clap from further away or less hard.
* The attempt was invalid anyway: a handling/tap transient at 1.1 s (−19 dBFS peak), 1.4 s before the clap.
* Same file analysed on the phone and on the desktop CLI: identical margins (13 / 33 / 44 / 53 / 53 dB).
* Diagnostic only: with the tap trimmed off, T20 reads ≈ 0.75 / 0.70 / 0.64 / 0.61 s at
  500 / 1000 / 2000 / 4000 Hz (forward filter), all *questionable* because the level after the decay
  was 8–16 dB above the pre-clap background; 250 Hz fails the 35 dB margin. Time-reversed filtering
  differs by up to ~9 % at 500 Hz. This is one clap, not a measurement.

**Ten claps at one position (kitchen table), reasons for failures** (algorithm 0.2.0 re-analysis;
APK before the 3 s countdown and live level meter):

* 6 of 9 claps in the first session peaked at −0.1…−0.3 dBFS (no headroom).
* 4 had handling noise in the pre-roll (button tap, movement), 1 had ambient noise throughout
  (−46 dBFS, something running), 2 had a small noise 2.4 s / 2.1 s after the clap, after the decay.
* The late noise discarded whole attempts before 0.2.0; clap #6 is now valid.
* 3 claps are valid in all of 500–4000 Hz (peaks −1.1 / −2.9 / −4.4 dBFS, background −66 to −73 dBFS):
  T20 500 Hz 0.80 / 0.70 / 0.78 s, 1 kHz 0.76 / 0.71 / 0.74 s, 2 kHz 0.68 / 0.65 / 0.66 s,
  4 kHz 0.59 / 0.57 / 0.61 s. Three claps are not a repeatability estimate.
* Countermeasures added: 3 s "get ready" countdown with the microphone off, live level meter with
  a −12…−2 dBFS target, "stay still until the phone buzzes", and the per-band late-event rule.

Still to do:

* Microphone check screen: record the claims (UNPROCESSED property, formats, effects, inputs).
* Test capture: confirm the actual source, sample rate and encoding, the routed device, effect
  states and the platform recording-configuration notes. Confirm there are no capture events.
* Behaviour checks the API cannot prove:
  * Play a stepped-level tone or noise from a separate speaker. Check that the dBFS levels change
    linearly (no AGC) and that the noise floor is not gated (no `DROPOUT` from a noise gate).
  * Make claps at increasing distances to find the clipping limit. Check that `CLIPPING` /
    `FLAT_TOP` appear before audibly distorted results.
  * Plug and unplug a wired headset, and start a call, during a capture: these should produce
    `ROUTE_CHANGED` / `SILENCED` and an invalid attempt.
* If UNPROCESSED is unsupported, note that every session is flagged as a fallback.

## 4. Unchanged-room repeatability ⏳

Do this before assessing any treatment.

* Two complete sessions (3 positions × 5 claps + background), room unchanged.
* Report per position and band: valid/attempted counts, medians, IQRs, and the difference between
  the session medians. This is the measured repeatability. **Do not** derive a tolerance from the
  synthetic tests.
* If T20 is rarely valid in the primary bands (e.g. because claps do not give 35 dB margin), report
  that honestly and move to controlled speaker excitation rather than relaxing the gates.

## 5. Sensitivity ⏳

* A substantial, reversible change (e.g. thick blankets or duvets over chairs and the table). Check
  that the comparison reports a change larger than the variability in the mid bands.
* Then revert the change and measure again: this should come out inconclusive.

## 6. Independent measurement chain ⏳ (where possible)

Measure the same positions with a separate speaker and a measurement microphone (e.g. REW sweeps).
This validates the whole chain, not only agreement on the same input file.

## Functional acceptance (app) ⏳ on device

* Baseline and treatment sessions completed offline and compared at matched positions.
* Invalid bands show reasons, not numbers.
* Route change and interruption (home button during a capture) are handled explicitly: the attempt
  is discarded on interruption and marked invalid on a route change.
* Recordings and versioned analyses survive a process restart. Re-analysis appends a new result and
  never replaces the recording (covered by unit tests in `core/sessions`).
* No level is labelled dB SPL (export covered by a unit test; UI reviewed).
* The microphone opens only from an explicit measurement or test-capture button.
