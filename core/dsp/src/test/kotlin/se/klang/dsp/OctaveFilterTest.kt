package se.klang.dsp

import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OctaveFilterTest {
    private fun db(v: Double) = 20 * log10(v)

    @Test
    fun `unity gain at centre and -3 dB at band edges`() {
        for (fs in listOf(44_100, 48_000)) {
            for (fc in listOf(250, 500, 1000, 2000, 4000)) {
                val f = OctaveFilter(fc.toDouble(), fs)
                // Geometric centre of the pre-warped edges maps back exactly to fc only approximately;
                // the normalisation point is within a fraction of a percent of fc.
                assertEquals(0.0, db(f.magnitudeAt(fc.toDouble())), 0.05, "centre $fc @ $fs")
                assertEquals(-3.01, db(f.magnitudeAt(fc / sqrt(2.0))), 0.05, "lower edge $fc @ $fs")
                assertEquals(-3.01, db(f.magnitudeAt(fc * sqrt(2.0))), 0.05, "upper edge $fc @ $fs")
            }
        }
    }

    @Test
    fun `sixth order skirts attenuate one octave outside the band edges`() {
        val f = OctaveFilter(1000.0, 48_000)
        // One octave beyond each edge (≈ 354 Hz and 2828 Hz) a 6th-order band-pass is well below -30 dB.
        assertTrue(db(f.magnitudeAt(1000 / sqrt(2.0) / 2)) < -30.0)
        assertTrue(db(f.magnitudeAt(1000 * sqrt(2.0) * 2)) < -30.0)
        assertEquals(3, f.sections.size)
    }

    @Test
    fun `impulse response matches the analytic magnitude (time-domain check)`() {
        val fs = 48_000
        val f = OctaveFilter(1000.0, fs)
        val tone = FloatArray(fs) { kotlin.math.sin(2 * Math.PI * 1000.0 * it / fs).toFloat() }
        val y = f.process(tone)
        var acc = 0.0
        for (i in fs / 2 until fs) acc += y[i] * y[i]
        val rms = sqrt(acc / (fs / 2))
        assertEquals(1 / sqrt(2.0), rms, 0.005)
    }

    @Test
    fun `time reversed filtering has the same magnitude`() {
        val fs = 48_000
        val f = OctaveFilter(2000.0, fs)
        val tone = FloatArray(fs) { kotlin.math.sin(2 * Math.PI * 2000.0 * it / fs).toFloat() }
        val y = f.process(tone, FilterDirection.TIME_REVERSED)
        var acc = 0.0
        for (i in fs / 4 until fs / 2) acc += y[i] * y[i]
        assertEquals(1 / sqrt(2.0), sqrt(acc / (fs / 4)), 0.005)
    }
}
