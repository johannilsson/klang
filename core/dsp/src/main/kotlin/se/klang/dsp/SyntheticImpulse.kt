package se.klang.dsp

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Deterministic synthetic impulse responses with analytically known decay, for validation.
 *
 * Fixture model (documented assumptions):
 *  - For each octave band b: Gaussian white noise (seeded) multiplied by an energy envelope that
 *    falls 60 dB in T_b seconds, i.e. amplitude exp(-3·ln(10)·t / T_b), then band-limited with the
 *    same octave filter design used by the analyser. The expected decay time in band b is T_b.
 *  - Optionally a second, slower decay ("double slope") per band, starting at a given level.
 *  - Bands are summed, the result is scaled to [peakAmplitude], and stationary white background
 *    noise at [Spec.noiseRmsDbfs] (mean-square convention) is added everywhere.
 *  - Silence before [Spec.preRollSeconds] contains background noise only.
 *
 * Adjacent bands leak into each other through the filter skirts, so fixtures with very different
 * T per band carry a small, systematic bias. This is a property of the fixture, not of rooms.
 */
object SyntheticImpulse {
    data class LateDecay(val seconds: Double, val levelDb: Double)

    enum class Excitation {
        /** Exponentially decaying Gaussian noise per band: realistic, but each realisation fluctuates. */
        DECAYING_NOISE,
        /**
         * Three exponentially damped sinusoids per band at fc·2^(-1/4), fc and fc·2^(1/4), with
         * seeded phases. Energy envelope is exactly exponential apart from beating ripple, giving a
         * low-variance fixture for accuracy targets.
         */
        DAMPED_TONES,
    }

    data class Spec(
        val excitation: Excitation = Excitation.DECAYING_NOISE,
        val sampleRate: Int = 48_000,
        val preRollSeconds: Double = 0.75,
        val durationSeconds: Double = 3.0,
        /** T60 per octave band centre. */
        val decaySeconds: Map<Int, Double> = mapOf(250 to 0.5, 500 to 0.5, 1000 to 0.5, 2000 to 0.5, 4000 to 0.5),
        /** Relative band levels (dB), to mimic a clap spectrum. */
        val bandLevelDb: Map<Int, Double> = emptyMap(),
        val lateDecay: Map<Int, LateDecay> = emptyMap(),
        val peakAmplitude: Double = 0.5,
        val noiseRmsDbfs: Double = -90.0,
        val seed: Long = 1L,
    )

    fun generate(spec: Spec): PcmSignal {
        val fs = spec.sampleRate
        val n = (spec.durationSeconds * fs).toInt()
        val onset = (spec.preRollSeconds * fs).toInt()
        val rng = Gaussian(spec.seed)
        val ir = DoubleArray(n)
        for ((fc, t60) in spec.decaySeconds.toSortedMap()) {
            if (spec.excitation == Excitation.DAMPED_TONES) {
                addDampedTones(ir, fc, t60, spec, onset, rng)
                continue
            }
            val raw = FloatArray(n)
            val gain = Math.pow(10.0, (spec.bandLevelDb[fc] ?: 0.0) / 20.0)
            val late = spec.lateDecay[fc]
            val lateGain = late?.let { Math.pow(10.0, it.levelDb / 20.0) } ?: 0.0
            for (i in onset until n) {
                val t = (i - onset).toDouble() / fs
                var env = Math.exp(-3.0 * ln(10.0) * t / t60)
                if (late != null) env = max(env, lateGain * Math.exp(-3.0 * ln(10.0) * t / late.seconds))
                raw[i] = (rng.next() * env * gain).toFloat()
            }
            val band = OctaveFilter(fc.toDouble(), fs).process(raw)
            for (i in 0 until n) ir[i] += band[i]
        }
        var peak = 0.0
        for (v in ir) peak = max(peak, Math.abs(v))
        val scale = if (peak > 0) spec.peakAmplitude / peak else 0.0
        val noiseRms = sqrt(Math.pow(10.0, spec.noiseRmsDbfs / 10.0))
        val noiseRng = Gaussian(spec.seed * 7919 + 13)
        val out = FloatArray(n) { (ir[it] * scale + noiseRng.next() * noiseRms).toFloat() }
        return PcmSignal(out, fs)
    }

    private fun addDampedTones(ir: DoubleArray, fc: Int, t60: Double, spec: Spec, onset: Int, rng: Gaussian) {
        val fs = spec.sampleRate
        val gain = Math.pow(10.0, (spec.bandLevelDb[fc] ?: 0.0) / 20.0)
        val late = spec.lateDecay[fc]
        val lateGain = late?.let { Math.pow(10.0, it.levelDb / 20.0) } ?: 0.0
        for (j in -1..1) {
            val f = fc * Math.pow(2.0, j / 4.0)
            val phase = 2 * Math.PI * ((rng.next() + 10.0) % 1.0)
            for (i in onset until ir.size) {
                val t = (i - onset).toDouble() / fs
                var env = Math.exp(-3.0 * ln(10.0) * t / t60)
                if (late != null) env = max(env, lateGain * Math.exp(-3.0 * ln(10.0) * t / late.seconds))
                ir[i] += gain * env * Math.cos(2 * Math.PI * f * t + phase)
            }
        }
    }

    /** Deterministic Gaussian generator (SplitMix64 + Box–Muller); avoids JVM-version-dependent Random. */
    class Gaussian(seed: Long) {
        private var state = seed
        private var spare = Double.NaN
        private fun nextLong(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        private fun uniform(): Double = ((nextLong() ushr 11) + 1).toDouble() / (1L shl 53).toDouble()
        fun next(): Double {
            if (!spare.isNaN()) { val s = spare; spare = Double.NaN; return s }
            val u1 = uniform(); val u2 = uniform()
            val r = sqrt(-2.0 * ln(u1))
            spare = r * Math.sin(2 * Math.PI * u2)
            return r * Math.cos(2 * Math.PI * u2)
        }
    }
}
