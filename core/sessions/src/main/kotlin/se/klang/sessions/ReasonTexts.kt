package se.klang.sessions

import se.klang.dsp.ReasonCode

/**
 * Plain-language texts for quality reasons, shared by the app screens and the summaries (#26), so
 * every screen gives the same advice. Advice never suggests clapping harder.
 */
object ReasonTexts {
    /** Practical advice per reason, or null when there is nothing the user can change. */
    fun advice(code: ReasonCode): String? = when (code) {
        ReasonCode.CLIPPING, ReasonCode.FULL_SCALE_SAMPLE, ReasonCode.NEAR_FULL_SCALE, ReasonCode.FLAT_TOP ->
            "Too loud for the microphone. Clap less hard or stand a little further from the phone."
        ReasonCode.INSUFFICIENT_SNR_MARGIN, ReasonCode.INSUFFICIENT_DECAY_RANGE ->
            "Background too loud for this band. Turn off fans, the dishwasher or the extractor; do not clap harder."
        ReasonCode.ADDITIONAL_IMPULSE, ReasonCode.BACKGROUND_TRANSIENT ->
            "Another sound was picked up. Clap once, then stay still and quiet until recording ends."
        ReasonCode.INSUFFICIENT_PRE_ROLL -> "Wait for the \"Clap now\" prompt before clapping."
        ReasonCode.INSUFFICIENT_TAIL -> "Clap promptly when asked so the decay fits in the recording."
        ReasonCode.CAPTURE_DISCONTINUITY, ReasonCode.DROPOUT ->
            "The recording was disturbed (audio route change, interruption or dropped samples). Try again without touching the phone."
        ReasonCode.NO_IMPULSE_FOUND -> "No clap detected. Clap once, clearly, after the prompt."
        else -> null
    }

    /**
     * What went wrong, as a short phrase that completes "… had …" or "… because of …", e.g.
     * "two had another sound during the recording".
     */
    fun problem(code: ReasonCode): String = when (code) {
        ReasonCode.CLIPPING, ReasonCode.FULL_SCALE_SAMPLE, ReasonCode.NEAR_FULL_SCALE, ReasonCode.FLAT_TOP -> "a clap too loud for the microphone"
        ReasonCode.ADDITIONAL_IMPULSE -> "another sound during the recording (a tap, movement or a second clap)"
        ReasonCode.BACKGROUND_TRANSIENT -> "a noise before the clap"
        ReasonCode.INSUFFICIENT_SNR_MARGIN, ReasonCode.INSUFFICIENT_DECAY_RANGE -> "too little margin above the background noise"
        ReasonCode.INSUFFICIENT_PRE_ROLL -> "a clap before the prompt"
        ReasonCode.INSUFFICIENT_TAIL -> "a decay that did not fit in the recording"
        ReasonCode.CAPTURE_DISCONTINUITY, ReasonCode.DROPOUT -> "a disturbed recording (route change, interruption or dropped samples)"
        ReasonCode.NO_IMPULSE_FOUND -> "no clap detected"
        ReasonCode.SAMPLE_RATE_TOO_LOW -> "a sample rate too low for the analysis"
        ReasonCode.NON_PHYSICAL_SLOPE, ReasonCode.DECAY_OUT_OF_RANGE -> "a decay that could not be fitted"
        ReasonCode.NON_LINEAR_DECAY, ReasonCode.CURVED_DECAY -> "an irregular decay"
        ReasonCode.TRUNCATION_NOT_CONVERGED, ReasonCode.TAIL_NOISE_MISMATCH -> "an unsteady background"
        ReasonCode.FILTER_RINGING_LIMIT -> "a decay too short for this band's filter"
        ReasonCode.REPEAT_OUTLIER -> "a value far from the others"
        ReasonCode.TOO_FEW_VALID_ATTEMPTS -> "too few usable claps"
    }

    /** Why one band has no value, completing "250 Hz: not measured (…)". */
    fun bandProblem(code: ReasonCode): String = when (code) {
        ReasonCode.INSUFFICIENT_SNR_MARGIN, ReasonCode.INSUFFICIENT_DECAY_RANGE -> "the claps were too weak there compared with the background"
        else -> problem(code)
    }
}
