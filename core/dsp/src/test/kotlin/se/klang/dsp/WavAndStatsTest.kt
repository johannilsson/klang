package se.klang.dsp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WavAndStatsTest {
    @Test
    fun `wav round trip for every encoding`() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 0.25f, -1f, 0.999f)
        for (enc in WavEncoding.entries) {
            val bytes = Wav.toBytes(samples, 48_000, enc)
            val d = Wav.read(bytes)
            assertEquals(48_000, d.info.sampleRate)
            assertEquals(enc, d.info.encoding)
            assertEquals(samples.size, d.signal.size)
            val tol = if (enc == WavEncoding.PCM_16) 1.0 / 32768 else 1e-6
            for (i in samples.indices) assertEquals(samples[i].toDouble(), d.signal.samples[i].toDouble(), tol, "$enc[$i]")
        }
    }

    @Test
    fun `pcm16 full scale reads as clip level`() {
        val d = Wav.read(Wav.toBytes(floatArrayOf(1f, -1f), 48_000, WavEncoding.PCM_16))
        assertTrue(d.signal.samples[0] >= 0.999f)
        assertEquals(-1f, d.signal.samples[1])
    }

    @Test
    fun `quartiles use linear interpolation`() {
        val s = RepeatSummary.of(listOf(0.50, 0.40, 0.45, 0.55, 0.60))!!
        assertEquals(0.50, s.median, 1e-12)
        assertEquals(0.45, s.q1, 1e-12)
        assertEquals(0.55, s.q3, 1e-12)
        assertEquals(5, s.count)
        assertEquals(RepeatSummary.of(listOf(1.0, 2.0, 3.0, 4.0))!!.median, 2.5)
    }

    @Test
    fun `outliers are flagged not removed`() {
        val p = RepeatPolicy()
        assertEquals(setOf(4), p.outliers(listOf(0.5, 0.52, 0.48, 0.5, 0.9)))
        assertEquals(emptySet(), p.outliers(listOf(0.5, 0.9)))
    }

    @Test
    fun `levels are dBFS`() {
        assertEquals(-6.02, Levels.peakDbfs(floatArrayOf(0.5f, -0.25f)), 0.01)
        assertEquals(-3.01, Levels.rmsDbfs(FloatArray(48_000) { kotlin.math.sin(2 * Math.PI * 1000 * it / 48_000).toFloat() }), 0.01)
        assertContentEquals(floatArrayOf(1f), floatArrayOf(1f))
    }
}
