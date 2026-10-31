package se.klang.dsp

/** Quality state of a band, an attempt or a comparison cell. Ordered from best to worst. */
enum class QualityState { VALID, QUESTIONABLE, INVALID }

/** Machine-readable reasons. Messages are for people; codes are for logic, export and tests. */
enum class ReasonCode {
    // Attempt level
    NO_IMPULSE_FOUND,
    INSUFFICIENT_PRE_ROLL,
    INSUFFICIENT_TAIL,
    CLIPPING,
    FULL_SCALE_SAMPLE,
    NEAR_FULL_SCALE,
    FLAT_TOP,
    CAPTURE_DISCONTINUITY,
    DROPOUT,
    ADDITIONAL_IMPULSE,
    BACKGROUND_TRANSIENT,
    SAMPLE_RATE_TOO_LOW,

    // Band level
    INSUFFICIENT_SNR_MARGIN,
    INSUFFICIENT_DECAY_RANGE,
    NON_PHYSICAL_SLOPE,
    DECAY_OUT_OF_RANGE,
    NON_LINEAR_DECAY,
    CURVED_DECAY,
    TRUNCATION_NOT_CONVERGED,
    TAIL_NOISE_MISMATCH,
    FILTER_RINGING_LIMIT,

    // Repeat / session level
    REPEAT_OUTLIER,
    TOO_FEW_VALID_ATTEMPTS,
}

data class QualityReason(
    val code: ReasonCode,
    val severity: QualityState,
    val message: String,
    val value: Double? = null,
    val threshold: Double? = null,
)

fun List<QualityReason>.worstState(): QualityState =
    maxOfOrNull { it.severity } ?: QualityState.VALID

/**
 * A line fitted to the energy-decay curve, and the 60 dB time extrapolated from it.
 *
 * [seconds] = -60 / [slopeDbPerSecond]. It is an extrapolation, not the elapsed time of the
 * fitted drop.
 */
data class DecayFit(
    val seconds: Double,
    val slopeDbPerSecond: Double,
    val interceptDb: Double,
    /** Nominal fit range on the normalised EDC, e.g. -5 and -25 dB for T20. */
    val rangeStartDb: Double,
    val rangeEndDb: Double,
    /** Times (from onset) where the EDC crosses the range limits. */
    val startSeconds: Double,
    val endSeconds: Double,
    val rSquared: Double,
    /** 1000·(1 - r²), per mille. */
    val nonLinearityPermille: Double,
    val residualRmsDb: Double,
)

/** Downsampled curves for display and export; times are seconds from onset. */
data class DecayCurve(
    val stepSeconds: Double,
    /** Normalised Schroeder energy-decay curve, dB. Ends at the truncation point. */
    val edcDb: FloatArray,
    /** Short-time band level relative to the band peak, dB, to the end of the recording. */
    val envelopeDb: FloatArray,
    /** Background level on the same scale as [envelopeDb]. */
    val backgroundDb: Double,
)

data class BandResult(
    val centerHz: Int,
    val extended: Boolean,
    val state: QualityState,
    val reasons: List<QualityReason>,
    /** Primary output. Null unless the band passes the T20 gates (state is VALID or QUESTIONABLE). */
    val t20: DecayFit?,
    /** Secondary, not yet validated. Conventional EDT: 0 to -10 dB fit, extrapolated to 60 dB. */
    val edt: DecayFit?,
    /** Diagnostic only (curvature check). Never presented as the primary result. */
    val t30: DecayFit?,
    /** Peak band level minus background band level, dB. Definition in docs/dsp.md. */
    val marginDb: Double?,
    val peakLevelDbfs: Double?,
    val backgroundLevelDbfs: Double?,
    /** Where the Schroeder integration was truncated, seconds from onset. */
    val truncationSeconds: Double?,
    /** Energy added for the truncated tail, dB relative to the total. */
    val compensationDb: Double?,
    val curve: DecayCurve?,
)

data class AttemptAnalysis(
    val configuration: AnalysisConfiguration,
    val sampleRate: Int,
    val durationSeconds: Double,
    /** Overall state = worst of attempt-level reasons; per-band states are in [bands]. */
    val state: QualityState,
    val reasons: List<QualityReason>,
    val onsetSeconds: Double?,
    val peakDbfs: Double,
    val backgroundRmsDbfs: Double?,
    val bands: List<BandResult>,
)
