package se.klang.dsp

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Second-order IIR section, coefficients normalised so a0 = 1. */
data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

/**
 * Octave-band filter: digital Butterworth band-pass.
 *
 * Design (documented, deterministic):
 *  - Band edges f1 = fc / sqrt(2), f2 = fc * sqrt(2) (base-2 octaves, exact centre frequencies
 *    500, 1000, 2000 Hz rather than the IEC base-10 values, which differ by < 1 %).
 *  - Analog Butterworth low-pass prototype of order [order] (default 3) is transformed to a
 *    band-pass of order 2·[order], with edges pre-warped for the bilinear transform so the -3 dB
 *    points land exactly on f1 and f2.
 *  - Bilinear transform to z; realised as [order] cascaded biquads in transposed direct form II,
 *    each with zeros at z = +1 and z = -1. Gain is normalised to exactly 0 dB at the digital
 *    centre frequency.
 *  - Processing is causal, forward-in-time by default (see [FilterDirection]).
 *
 * A 6th-order octave band-pass Butterworth meets the general shape of IEC 61260 class-1 octave
 * filters near the passband but is not claimed to be compliant.
 */
class OctaveFilter(val centerHz: Double, val sampleRate: Int, val order: Int = 3) {
    val lowerEdgeHz: Double = centerHz / sqrt(2.0)
    val upperEdgeHz: Double = centerHz * sqrt(2.0)
    val bandwidthHz: Double get() = upperEdgeHz - lowerEdgeHz
    val sections: List<Biquad>

    init {
        require(order in 1..8) { "order must be in 1..8" }
        require(upperEdgeHz < sampleRate / 2.0) {
            "Band ${centerHz} Hz upper edge ${upperEdgeHz} Hz is above Nyquist for $sampleRate Hz"
        }
        sections = design()
    }

    private fun design(): List<Biquad> {
        val fs = sampleRate.toDouble()
        val w1 = 2.0 * fs * tan(PI * lowerEdgeHz / fs)
        val w2 = 2.0 * fs * tan(PI * upperEdgeHz / fs)
        val w0sq = w1 * w2
        val bw = w2 - w1
        val result = ArrayList<Biquad>(order)
        for (k in 1..order) {
            // Prototype pole on the unit circle, left half-plane.
            val theta = PI * (2.0 * k + order - 1) / (2.0 * order)
            val pr = cos(theta)
            val pi = sin(theta)
            // Band-pass poles: s^2 - p·bw·s + w0^2 = 0  ->  s = (p·bw ± sqrt((p·bw)^2 - 4 w0^2)) / 2
            val ar = pr * bw
            val ai = pi * bw
            // discriminant d = (ar + j ai)^2 - 4 w0sq
            val dr = ar * ar - ai * ai - 4.0 * w0sq
            val di = 2.0 * ar * ai
            val (sr, si) = complexSqrt(dr, di)
            for (sign in intArrayOf(1, -1)) {
                val qr = (ar + sign * sr) / 2.0
                val qi = (ai + sign * si) / 2.0
                // Keep one pole of each conjugate pair (upper half-plane); its conjugate is implied.
                if (qi <= 0.0) continue
                val (zr, zi) = bilinear(qr, qi, fs)
                result += Biquad(1.0, 0.0, -1.0, -2.0 * zr, zr * zr + zi * zi)
            }
        }
        check(result.size == order) { "Unexpected pole count ${result.size}" }
        // Normalise to unity gain at the digital centre frequency.
        val fcDigital = fs / PI * atan(sqrt(w0sq) / (2.0 * fs))
        val g = magnitude(result, fcDigital, fs)
        val perSection = Math.pow(1.0 / g, 1.0 / order)
        return result.map { it.copy(b0 = it.b0 * perSection, b2 = it.b2 * perSection) }
    }

    /** |H(e^{jw})| at frequency [hz]. */
    fun magnitudeAt(hz: Double): Double = magnitude(sections, hz, sampleRate.toDouble())

    /** Filters [input]; the returned array has the same length. */
    fun process(input: FloatArray, direction: FilterDirection = FilterDirection.FORWARD): DoubleArray {
        val n = input.size
        val x = DoubleArray(n)
        if (direction == FilterDirection.FORWARD) {
            for (i in 0 until n) x[i] = input[i].toDouble()
        } else {
            for (i in 0 until n) x[i] = input[n - 1 - i].toDouble()
        }
        for (s in sections) {
            var z1 = 0.0
            var z2 = 0.0
            for (i in 0 until n) {
                val xi = x[i]
                val y = s.b0 * xi + z1
                z1 = s.b1 * xi - s.a1 * y + z2
                z2 = s.b2 * xi - s.a2 * y
                x[i] = y
            }
        }
        if (direction == FilterDirection.TIME_REVERSED) x.reverse()
        return x
    }

    companion object {
        private fun complexSqrt(re: Double, im: Double): Pair<Double, Double> {
            val m = hypot(re, im)
            val r = sqrt((m + re) / 2.0)
            val i = sqrt(((m - re) / 2.0).coerceAtLeast(0.0)).let { if (im < 0) -it else it }
            return r to i
        }

        private fun bilinear(sr: Double, si: Double, fs: Double): Pair<Double, Double> {
            // z = (2fs + s) / (2fs - s)
            val nr = 2.0 * fs + sr
            val ni = si
            val dr = 2.0 * fs - sr
            val di = -si
            val den = dr * dr + di * di
            return ((nr * dr + ni * di) / den) to ((ni * dr - nr * di) / den)
        }

        internal fun magnitude(sections: List<Biquad>, hz: Double, fs: Double): Double {
            val w = 2.0 * PI * hz / fs
            val c1 = cos(w); val s1 = sin(w)
            val c2 = cos(2 * w); val s2 = sin(2 * w)
            var mag = 1.0
            for (s in sections) {
                // H = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2), z^-1 = e^{-jw}
                val nr = s.b0 + s.b1 * c1 + s.b2 * c2
                val ni = -(s.b1 * s1 + s.b2 * s2)
                val dr = 1.0 + s.a1 * c1 + s.a2 * c2
                val di = -(s.a1 * s1 + s.a2 * s2)
                mag *= hypot(nr, ni) / hypot(dr, di)
            }
            return mag
        }
    }
}

/**
 * FORWARD is ordinary causal filtering: filter ringing appears after the impulse and can lengthen
 * very short decays (rule of thumb: bandwidth x decay time should exceed ~16).
 * TIME_REVERSED filters the reversed signal and reverses the result, moving the filter's own decay
 * before the onset; useful as a cross-check for short low-frequency decays.
 */
enum class FilterDirection { FORWARD, TIME_REVERSED }
