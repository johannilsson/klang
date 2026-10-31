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

## 3. Device capability on the target phone ⏳

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
