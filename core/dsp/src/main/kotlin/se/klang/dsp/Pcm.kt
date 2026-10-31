package se.klang.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * A mono PCM recording, normalised so that digital full scale is 1.0.
 *
 * Samples are stored as Float (what Android's ENCODING_PCM_FLOAT delivers and
 * what PCM16 converts to losslessly); all analysis is done in Double.
 *
 * @property discontinuities sample indices where the capture layer knows the
 *   timeline is broken (buffer overrun, short read, route change). The DSP
 *   treats any entry as invalidating the attempt; it never tries to splice.
 */
class PcmSignal(
    val samples: FloatArray,
    val sampleRate: Int,
    val discontinuities: List<Int> = emptyList(),
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
    }

    val size: Int get() = samples.size
    val durationSeconds: Double get() = samples.size.toDouble() / sampleRate

    fun scaled(gain: Double): PcmSignal =
        PcmSignal(FloatArray(size) { (samples[it] * gain).toFloat() }, sampleRate, discontinuities)
}

/** Relative digital levels. These are dB relative to digital full scale, never dB SPL. */
object Levels {
    /** Floor used instead of -infinity for silent input. */
    const val FLOOR_DB = -200.0

    fun powerToDb(power: Double): Double = if (power <= 0.0) FLOOR_DB else max(FLOOR_DB, 10.0 * log10(power))

    /** Peak level, dBFS: 20·log10(max |x|), full scale = 1.0. */
    fun peakDbfs(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var peak = 0.0
        for (i in from until to) peak = max(peak, abs(x[i].toDouble()))
        return if (peak <= 0.0) FLOOR_DB else 20.0 * log10(peak)
    }

    /**
     * RMS level, dBFS: 10·log10(mean x²), full scale = 1.0. Note: this is the
     * plain mean-square convention, so a full-scale sine reads -3.01 dBFS.
     */
    fun rmsDbfs(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        if (to <= from) return FLOOR_DB
        var acc = 0.0
        for (i in from until to) {
            val v = x[i].toDouble(); acc += v * v
        }
        return powerToDb(acc / (to - from))
    }
}
