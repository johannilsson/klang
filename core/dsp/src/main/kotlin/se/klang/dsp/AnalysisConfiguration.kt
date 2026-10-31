package se.klang.dsp

/**
 * Every threshold the analysis uses, in one versioned value. Results always carry the
 * configuration that produced them, so a recording can be re-analysed later with a newer
 * version without losing which algorithm produced an older result.
 *
 * Changing any default here is an algorithm change: bump [version].
 * See docs/dsp.md for the rationale behind each value. They are engineering starting points,
 * not standards requirements.
 */
data class AnalysisConfiguration(
    val algorithmId: String = ALGORITHM_ID,
    val version: String = VERSION,

    // ---- Bands ----
    /** Octave centre frequencies analysed. 250 and 4000 Hz are "extended" bands. */
    val bandsHz: List<Int> = listOf(250, 500, 1000, 2000, 4000),
    /** Initial product priority bands; others are shown only if they pass the same gates. */
    val primaryBandsHz: Set<Int> = setOf(500, 1000, 2000),
    /** Butterworth prototype order; the band-pass order is twice this. */
    val filterOrder: Int = 3,
    val filterDirection: FilterDirection = FilterDirection.FORWARD,

    // ---- Segmentation ----
    /** Minimum background (pre-onset) audio required in an attempt. */
    val minPreRollSeconds: Double = 0.25,
    /** Audio at the very start of the recording skipped for noise estimation (filter start-up). */
    val filterSettleSeconds: Double = 0.05,
    /** Gap left between the background segment and the detected onset. */
    val onsetGuardSeconds: Double = 0.01,
    /** Minimum audio required after onset (decay plus noise-only tail). */
    val minPostOnsetSeconds: Double = 1.0,
    /**
     * Onset = earliest sample within [onsetSearchSeconds] before the largest sample with
     * |x| >= peak + [onsetThresholdDb]. The search is long because in a slow decay the largest
     * single sample can occur well after the true start.
     */
    val onsetThresholdDb: Double = -20.0,
    val onsetSearchSeconds: Double = 0.5,
    /** The onset threshold is also at least this far above the RMS of the first [minPreRollSeconds] of background. */
    val onsetAboveBackgroundDb: Double = 15.0,

    // ---- Capture integrity ----
    /** |x| at or above this is treated as at digital full scale. */
    val clipLevel: Double = 0.999,
    /** This many consecutive full-scale samples invalidates the attempt; fewer is questionable. */
    val clipRunInvalid: Int = 2,
    /** Consecutive identical samples, above [flatTopMinLevel], indicating analog/limiter clipping. */
    val flatTopRun: Int = 4,
    val flatTopMinLevel: Double = 0.3,
    /** A run of exact zeros this long (in otherwise non-silent audio) is treated as a dropout. */
    val dropoutZeroRunSeconds: Double = 0.002,

    // ---- Contamination ----
    /** Broadband envelope window used for contamination checks. */
    val contaminationWindowSeconds: Double = 0.005,
    /** Ignore this long after the onset (the clap's own early structure). */
    val secondImpulseHoldoffSeconds: Double = 0.03,
    /** After the peak, a window this far above the maximum of the preceding look-back is a second event... */
    val secondImpulseRiseDb: Double = 6.0,
    val secondImpulseLookbackSeconds: Double = 0.05,
    /** ...if it is also at least this far above the background level. */
    val secondImpulseAboveNoiseDb: Double = 15.0,
    /** A pre-onset window within this distance of the main peak is a separate impulse. */
    val preOnsetImpulseWithinDb: Double = 20.0,
    /** A pre-onset window this far above the median background window is a background transient. */
    val backgroundTransientDb: Double = 15.0,

    // ---- Per-band decay analysis ----
    /** Required margin (peak band level minus background band level), see docs/dsp.md. */
    val minMarginT20Db: Double = 35.0,
    val minMarginEdtDb: Double = 20.0,
    /** T30 (diagnostic only, used for the curvature check) needs this margin. */
    val minMarginT30Db: Double = 45.0,
    /** Envelope window = max(minEnvelopeWindowSeconds, envelopeCycles / fc). */
    val minEnvelopeWindowSeconds: Double = 0.010,
    val envelopeCycles: Double = 8.0,
    /** Truncation-point search: late-decay fit stops this far above the background level. */
    val truncationHeadroomDb: Double = 10.0,
    /** Late-decay fit covers this many dB above the headroom level. */
    val truncationFitSpanDb: Double = 20.0,
    val truncationMaxIterations: Int = 8,
    /** The EDC at the truncation point must be at least this far below the T20 fit end (-25 dB). */
    val minEdcHeadroomBelowFitDb: Double = 5.0,
    /** Background level estimated from the noise tail may exceed the pre-onset level by this much. */
    val tailNoiseMismatchDb: Double = 6.0,

    // ---- Fit quality ----
    /** ISO 3382-style non-linearity xi = 1000·(1 - r²), per mille. */
    val maxNonLinearityPermille: Double = 10.0,
    /** Curvature C = 100·(T30/T20 - 1) %, only when T30 is available. */
    val maxCurvaturePercent: Double = 10.0,
    val minDecaySeconds: Double = 0.05,
    val maxDecaySeconds: Double = 5.0,
    /** Bandwidth x T below this means filter ringing may dominate (forward filtering). */
    val minBandwidthTimeProduct: Double = 16.0,

    // ---- Output ----
    /** Time resolution of the decay curves returned for display. */
    val curveStepSeconds: Double = 0.002,
) {
    companion object {
        const val ALGORITHM_ID = "klang.schroeder-truncated"
        const val VERSION = "0.1.0"
    }
}
