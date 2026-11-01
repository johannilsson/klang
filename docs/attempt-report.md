# The attempt screen: verdict, graph and findings

Implementation: `core/sessions` (`AttemptReport.kt`, `RecordingTimeline.kt`); screens in `app/.../ui/screens`
(`AttemptScreen`, `SessionScreen`, `CapabilityScreen`) and `ui/RecordingGraph.kt`. The texts are fixed rules in
core with their own tests, like the [summaries](summaries.md). Nothing here changes an analysis, a quality gate
or a comparison verdict: it only describes what the analysis already says.

## What the screens show

* **Attempt screen**: the verdict ("Usable clap", "Usable with caution", "Not usable: …") with one sentence and
  the facts as chips; a graph of the recording; each distinct problem once with its advice; the bands, each
  opening to its margin, fit and decay curve; and, folded away, the technical details (capture settings,
  algorithm, the secondary EDT).
* **Session screen**: each recording is a row with the same verdict word, one line (why it is not usable, or the T20
  values) and a small graph of the recording. The "N of 5 usable claps" count uses the same verdict.
* **Microphone check**: a test capture shows the same result, graph and findings.

## Verdict

A clap is **usable** when the 500, 1000 and 2000 Hz bands all have a value (an invalid band has none). It is
**usable with caution** when, in addition, a reason that affects the speech range or the whole recording is only
questionable (a very loud clap, an irregular decay in a speech band). Otherwise it is **not usable**. A band
outside the speech range that could not be measured (typically 250 Hz with a handclap) is noted, but does not
change the verdict. This is the same rule the summaries use for "usable claps".

## One problem, once

The analysis gives reasons per band, and one cause (a noisy room) shows up as several reasons in every band.
`AttemptReporter` groups them by cause and states each group once, with its advice and the bands it affects:

| Group | Reasons |
|---|---|
| Noise | `INSUFFICIENT_SNR_MARGIN`, `INSUFFICIENT_DECAY_RANGE` |
| Other sound | `ADDITIONAL_IMPULSE`, `BACKGROUND_TRANSIENT` |
| Too loud | `CLIPPING`, `FLAT_TOP`, `FULL_SCALE_SAMPLE`, `NEAR_FULL_SCALE` |
| Timing | `INSUFFICIENT_PRE_ROLL`, `INSUFFICIENT_TAIL`, `NO_IMPULSE_FOUND` |
| Disturbed | `CAPTURE_DISCONTINUITY`, `DROPOUT` |
| Decay shape | `NON_LINEAR_DECAY`, `CURVED_DECAY`, `TRUNCATION_NOT_CONVERGED`, `TAIL_NOISE_MISMATCH`, `FILTER_RINGING_LIMIT`, `NON_PHYSICAL_SLOPE`, `DECAY_OUT_OF_RANGE` |

A test checks that no advice text is repeated in one report. What was fine (clap level, nothing else louder than
the room, no capture events, a later sound that does not matter) is listed after the problems.

Two judgement calls, both tested:

* **A sound in the quiet part raises the measured background.** The analyser judges every band against the
  background level, which then includes the tap, so every band also fails the noise check. When the quiet part
  of the recording is at least 10 dB below the measured background and a sound before the clap was rejected, the
  noise failure is reported as a consequence of that sound, not as a noisy room, and the background chip shows
  the room's real level.
* **A quiet clap is only flagged when it matters.** A clap peaking below −12 dBFS is called quiet only if the
  noise check failed; in a very quiet room a quiet clap measures fine.

## The graph

`RecordingTimeline` is the short-time level of the recording, RMS per 50 ms in dBFS (relative to digital full
scale, never SPL), the same convention as an analysis' background level, so the dashed background line and the
curve agree. The clap's sample peak is drawn as a cap above its window, because a window's RMS is lower than the
instantaneous peak. The green band is the good clap peak range (−12 to −2 dBFS, `AttemptReportPolicy`, also used
by the live level meter).

The marked sounds are found for display only: a window 15 dB above the typical quiet window before the clap, or,
after the clap has faded to within 6 dB of it, anywhere later. The first is "other sound"; the second is "later
sound: no effect", because once the clap has faded into the background a sound cannot change a band's result.
The verdict never comes from these marks; it comes from the analysis' reasons. The thresholds are in
`RecordingTimeline.Policy`.

## Wording

As for the summaries: no "significant", "confidence", "certain", "dB SPL" or EDT in the verdict and findings (EDT
appears only in the folded technical details, labelled secondary and not validated), and no number for an
invalid band. A test runs every reason code through the rules and fails on those words or on a stale value of an
invalid band.
