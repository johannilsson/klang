# DSP: decay estimation from a single impulse

Implementation: `core/dsp` (`se.klang.dsp.DecayAnalyzer`). Pure Kotlin/JVM, no Android or I/O
dependencies, deterministic. Every threshold lives in `AnalysisConfiguration` (algorithm id
`klang.schroeder-truncated`, version `0.1.0`); every stored result carries the full configuration
that produced it. Changing a default is an algorithm change and must bump the version.

The thresholds below are engineering starting points chosen for this prototype. They are not
requirements of ISO 3382 or any other standard, and they still need tuning against real
recordings (see [validation-plan.md](validation-plan.md)).

## Input

Mono PCM, normalised so that digital full scale is 1.0 (float, or PCM16 / 32768). One recording =
one attempt: at least 0.25 s of background, then the clap, then at least 1.0 s of decay and noise
tail. The app records 5 s with the clap prompted at 1.5 s. The capture layer passes the sample
positions of known timeline breaks (overrun, short read, route change, system silencing) as
`PcmSignal.discontinuities`.

## 1. Integrity checks (whole recording)

| Check | Rule | Result |
|---|---|---|
| Clipping | ≥ 2 consecutive samples with \|x\| ≥ 0.999 | attempt **invalid** (`CLIPPING`) |
| Isolated full-scale samples | 1 sample run with \|x\| ≥ 0.999 | **questionable** (`FULL_SCALE_SAMPLE`) |
| Flat top | ≥ 4 consecutive identical samples with \|x\| ≥ 0.3 (analogue clipping or a limiter below full scale) | **invalid** (`FLAT_TOP`) |
| Dropout | run of exact zeros ≥ 2 ms in a recording that is not entirely silent (lost buffers or a device noise gate) | **invalid** (`DROPOUT`) |
| Capture discontinuity | any discontinuity reported by the capture layer | **invalid** (`CAPTURE_DISCONTINUITY`) |

## 2. Impulse detection and segmentation

* **Peak**: the sample with the largest \|x\|.
* **Onset**: the earliest sample within 0.5 s before the peak where \|x\| reaches the onset level.
  The onset level is the higher of (peak − 20 dB) and (early background RMS + 15 dB), capped at the
  peak. The early background RMS is taken over the first 0.25 s after the settle time. The search is
  long because in a slow decay the largest single sample can occur ~0.2 s after the true start. The
  background floor stops noise samples in a noisy room being taken for the onset.
* **Background segment**: from 50 ms after the start of the recording (filter start-up excluded) to
  10 ms before the onset. It must be at least 0.25 s long, otherwise `INSUFFICIENT_PRE_ROLL`.
* **Tail**: at least 1.0 s after the onset, otherwise `INSUFFICIENT_TAIL`.
* If the peak is less than 20 dB above the broadband background RMS: `NO_IMPULSE_FOUND`.

## 3. Contamination checks (broadband, 5 ms mean-square windows)

* **Pre-onset impulse**: a background window at least 15 dB above the median background window
  *and* within 20 dB of the peak window → **invalid** (`ADDITIONAL_IMPULSE`). A window ≥ 15 dB
  above the median but not that loud → **questionable** (`BACKGROUND_TRANSIENT`).
* **Second event after onset**: from 30 ms after the onset, a window at least 6 dB above the
  *maximum* of the preceding 50 ms, and at least 15 dB above the background median → **invalid**
  (`ADDITIONAL_IMPULSE`). Comparing against the recent maximum rather than a running minimum keeps
  beating between room modes from looking like a second event: beating dips and recovers inside its
  own decaying envelope, while a new clap jumps above it. Starting at the onset (not the peak) also
  catches a second clap that is louder than the first.

## 4. Octave-band filtering

* Centre frequencies 250, 500, 1000, 2000, 4000 Hz, with base-2 edges `fc/√2 … fc·√2`. 500, 1000 and
  2000 Hz are primary. 250 and 4000 Hz are flagged `extended` and pass through exactly the same
  gates; nothing is relaxed for them.
* Digital Butterworth band-pass: analogue low-pass prototype of order 3, low-pass → band-pass
  transform (band-pass order 6), edges pre-warped for the bilinear transform so the −3 dB points
  fall exactly on the band edges. Realised as 3 cascaded biquads (transposed direct form II, double
  precision), each with zeros at z = ±1. Gain is normalised to 0 dB at the centre frequency.
  Verified by tests: 0 dB at fc, −3.01 dB at both edges, < −30 dB one octave beyond each edge.
* **Direction**: forward (causal) by default. `FilterDirection.TIME_REVERSED` filters the reversed
  signal and reverses the result, which moves the filter's own ringing before the onset. Use it as a
  cross-check for short low-frequency decays (CLI: `--reversed`).
* **Edges**: the whole recording is filtered, so the start-up transient decays inside the 50 ms
  settle time before the background segment begins. The last 50 ms is excluded from tail and
  envelope analysis (start-up transient of the reversed filter).
* **Ringing guard**: with forward filtering, a band whose bandwidth × T20 is below 16 is
  **questionable** (`FILTER_RINGING_LIMIT`), because the filter's own decay may dominate.

## 5. Per band: margin, truncation and Schroeder integral

Let `p(n) = y(n)²` be the squared band signal.

**Background power** `N`: mean of `p` over the background segment.

**Envelope**: mean of `p` in non-overlapping windows from the onset. Window length =
max(10 ms, 8 cycles of fc): 32 ms at 250 Hz, 16 ms at 500 Hz, 10 ms above.

**Margin (source-to-background, dB)** = `10·log10(max envelope window / N)`.
Both terms are mean-square values of the same band-filtered signal. The peak term is the largest
envelope window at or after the onset, so its value depends on the window length above (a stated
part of the definition). T20 requires **≥ 35 dB** (`INSUFFICIENT_SNR_MARGIN`, invalid), EDT ≥ 20 dB,
and the diagnostic T30 ≥ 45 dB. 35 dB = the −25 dB fit end plus 10 dB headroom above the background.
It is a starting requirement and does not by itself guarantee validity.

**Truncation point** `tc`: an iterative, Lundeby-style search that uses the measured pre-onset
background instead of estimating the noise from the tail:

1. Fit a line (least squares, dB vs time) to the envelope from its peak until it first falls below
   `N + 10 dB`. The truncation point is where the line crosses `N`.
2. Repeat: refit using only envelope windows whose *predicted* level lies between `N + 10 dB` and
   `N + 30 dB` (the late decay). Recompute `tc`. Stop when `tc` moves by less than one window
   (at most 8 iterations; if it has not converged, the band is `TRUNCATION_NOT_CONVERGED`, questionable).
3. If `tc` would be beyond the end of the usable recording, the decay never reached the
   background: **invalid** (`INSUFFICIENT_TAIL`).

**Tail consistency**: mean power from `tc` + (time for a further 10 dB of decay) to the end is
compared with `N`. If it is more than 6 dB higher, the band is questionable
(`TAIL_NOISE_MISMATCH`: the noise was non-stationary or the decay is longer than estimated).

**Energy-decay curve (EDC)** with truncation and tail compensation:

```
E(n) = Σ_{k=n}^{nc-1} p(k) + C,        n = onset … nc-1
C    = P(tc) / k,   k = −slope_late · ln(10) / 10 / fs   (per sample)
EDC_dB(n) = 10 · log10( E(n) / E(onset) )
```

`P(tc)` is the late-decay line's power at `tc`, which equals `N` when the decay reaches the
background. `C` is the energy the exponential decay would still contain after `tc`, which the
truncation removed. **No noise is subtracted**, so the integrand can never go negative and no
clamping is needed. The noisy tail after `tc` is never integrated, because doing so bends the EDC
upwards and lengthens the apparent decay (covered by a test with a 6 s noisy tail).

## 6. Fits

Least-squares line on `EDC_dB` against time, between the **first** crossings of the range limits:

| Metric | Range | Reported value | Status |
|---|---|---|---|
| **T20** | −5 to −25 dB | `−60 / slope` (60 dB extrapolation, *not* the time of the 20 dB drop) | primary |
| EDT | 0 to −10 dB | `−60 / slope`, the conventional extrapolated EDT, *not* the literal duration of the first 10 dB | secondary, not yet validated |
| T30 | −5 to −35 dB | `−60 / slope` | diagnostic only (curvature check), never shown as a result |

A fit is attempted only if the EDC at the truncation point is at least 5 dB below the fit's lower
limit (for T20: ≤ −30 dB). This keeps the fit off the end of the EDC, which is dominated by the
compensation model. Otherwise `INSUFFICIENT_DECAY_RANGE` (invalid). Nothing else is ever
substituted: T20 is never replaced by EDT or by a shorter range.

## 7. Band gates (summary)

| Code | Rule | State |
|---|---|---|
| `INSUFFICIENT_SNR_MARGIN` | margin < 35 dB | invalid |
| `INSUFFICIENT_DECAY_RANGE` | EDC does not reach −30 dB before `tc`, or no decaying envelope | invalid |
| `INSUFFICIENT_TAIL` | decay does not reach the background before the recording ends | invalid |
| `NON_PHYSICAL_SLOPE` | fitted slope ≥ 0 | invalid |
| `DECAY_OUT_OF_RANGE` | T20 outside 0.05–5 s | invalid |
| `NON_LINEAR_DECAY` | ξ = 1000·(1 − r²) > 10 ‰ over the T20 range | questionable |
| `CURVED_DECAY` | \|100·(T30/T20 − 1)\| > 10 % (only when T30 is available) | questionable |
| `FILTER_RINGING_LIMIT` | forward filter and bandwidth × T20 < 16 | questionable |
| `TRUNCATION_NOT_CONVERGED`, `TAIL_NOISE_MISMATCH` | see above | questionable |
| attempt-level reasons | copied into every band | as above |

An **invalid** band never carries a T20 value. A **questionable** band keeps its value, its reasons
and its curve, so it can be inspected. EDT is reported when its own margin is met and the attempt
as a whole is not invalid, and it is always labelled as secondary.

## 8. Repeats and comparisons

Implemented in `core/sessions` (`Comparator`, `ComparisonPolicy` 0.1.0, `RepeatPolicy` 0.1.0).

* Each (position, band) cell in a session reports the median, the quartiles (linear interpolation,
  Hyndman–Fan type 7), the min/max and every usable value, plus counts of valid, questionable and
  invalid attempts with the most common invalid reasons.
* An attempt more than 25 % from its cell median is flagged as `outlier`. It is not removed.
* Change = treatment median − baseline median (seconds). Percentage reduction =
  `(baseline − treatment) / baseline × 100`, computed only when the baseline median exists and is > 0.
* Verdict per cell:
  * `INSUFFICIENT_DATA`: fewer than 3 usable attempts in either session.
  * `INCONCLUSIVE` ("Change is inconclusive") when the capture configurations differ, or there is no
    unchanged-room repeat session with ≥ 3 usable attempts, or |change| ≤ V, or |change| < 0.02 s,
    or the individual attempt ranges overlap.
    V = max(IQR baseline, IQR treatment, |median baseline − median repeat|).
  * Otherwise `DECREASE_/INCREASE_EXCEEDS_VARIABILITY`.

  This is a deliberately conservative, descriptive rule, **not** a significance test. No confidence
  percentages are given, since five attempts do not support a justified model.
* The overall summary is only a count of cells per verdict. Bands and positions are never averaged
  into a score, and missing cells are counted rather than dropped.

## Known limitations

* Small rooms are not diffuse, especially at low frequencies. Results are position- and
  method-dependent decay estimates, not an intrinsic RT60 of the room.
* Handclaps vary in spectrum and direction from clap to clap. Weak bands are expected to fail the
  margin gate, and they stay unavailable.
* On the noise fixture, 250 Hz decays at T60 = 0.3 s read ~9 % high on average, with a wide
  per-realisation spread ([validation-synthetic.md](validation-synthetic.md)). This is one reason
  250 Hz is an extended band.
* The second-impulse and background-transient thresholds, and the ξ limit, are untested on real
  kitchen recordings and are the first candidates for tuning.
* The comparison with Room EQ Wizard has not been done yet (see the validation plan).
