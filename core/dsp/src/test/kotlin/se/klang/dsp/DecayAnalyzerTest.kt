package se.klang.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Validation against synthetic fixtures with known decay (see [SyntheticImpulse] for assumptions).
 * The 5 % target is an engineering test target on clean synthetic data, not a real-room accuracy claim.
 */
class DecayAnalyzerTest {
    private val analyzer = DecayAnalyzer()
    private val bands = listOf(250, 500, 1000, 2000, 4000)

    private fun relErr(actual: Double, expected: Double) = abs(actual - expected) / expected

    private fun AttemptAnalysis.band(fc: Int) = bands.single { it.centerHz == fc }

    private fun AttemptAnalysis.describe() = buildString {
        append("attempt=$state ${reasons.map { it.code }}\n")
        bands.forEach { append("  ${it.centerHz}: ${it.state} t20=${it.t20?.seconds} margin=${it.marginDb} ${it.reasons.map { r -> r.code }}\n") }
    }

    @Test
    fun `clean fixtures - T20 within 5 percent across decay times and seeds`() {
        for (t in listOf(0.3, 0.5, 0.8, 1.2)) {
            for (seed in 1L..3L) {
                val spec = SyntheticImpulse.Spec(
                    excitation = SyntheticImpulse.Excitation.DAMPED_TONES,
                    decaySeconds = bands.associateWith { t },
                    durationSeconds = 0.75 + 2.5 * t + 0.6,
                    seed = seed,
                )
                val result = analyzer.analyze(SyntheticImpulse.generate(spec))
                for (fc in bands) {
                    val b = result.band(fc)
                    assertEquals(QualityState.VALID, b.state, "T=$t seed=$seed\n${result.describe()}")
                    val t20 = assertNotNull(b.t20)
                    assertTrue(relErr(t20.seconds, t) < 0.05, "T=$t band=$fc seed=$seed got ${t20.seconds}")
                    assertEquals(-5.0, t20.rangeStartDb)
                    assertEquals(-25.0, t20.rangeEndDb)
                }
            }
        }
    }

    @Test
    fun `decaying noise fixtures - unbiased on average, every realisation measurable`() {
        // Each noise realisation fluctuates like a real impulse response, so a single estimate can
        // be off by several percent (more in the 250 Hz band). The mean over seeds must be unbiased.
        for (t in listOf(0.4, 0.8)) {
            val ratios = bands.associateWith { mutableListOf<Double>() }
            for (seed in 1L..10L) {
                val spec = SyntheticImpulse.Spec(decaySeconds = bands.associateWith { t }, durationSeconds = 0.75 + 2.5 * t + 0.6, seed = seed)
                val r = analyzer.analyze(SyntheticImpulse.generate(spec))
                for (fc in bands) {
                    val b = r.band(fc)
                    assertTrue(b.state != QualityState.INVALID, "T=$t seed=$seed\n${r.describe()}")
                    ratios[fc]!! += b.t20!!.seconds / t
                }
            }
            for ((fc, v) in ratios) {
                val tol = if (fc == 250) 0.06 else 0.03
                assertTrue(abs(v.average() - 1.0) < tol, "T=$t band $fc mean ratio ${v.average()}")
            }
        }
    }

    @Test
    fun `frequency dependent decay is resolved per band`() {
        val decays = mapOf(250 to 0.7, 500 to 0.6, 1000 to 0.5, 2000 to 0.45, 4000 to 0.4)
        val spec = SyntheticImpulse.Spec(excitation = SyntheticImpulse.Excitation.DAMPED_TONES, decaySeconds = decays)
        val result = analyzer.analyze(SyntheticImpulse.generate(spec))
        for ((fc, t) in decays) {
            val t20 = assertNotNull(result.band(fc).t20, result.describe())
            assertTrue(relErr(t20.seconds, t) < 0.05, "band=$fc expected $t got ${t20.seconds}")
        }
    }

    @Test
    fun `edt is the conventional 60 dB extrapolation of the 0 to -10 dB fit`() {
        val spec = SyntheticImpulse.Spec(excitation = SyntheticImpulse.Excitation.DAMPED_TONES)
        val result = analyzer.analyze(SyntheticImpulse.generate(spec))
        val edt = assertNotNull(result.band(1000).edt)
        assertEquals(0.0, edt.rangeStartDb)
        assertEquals(-10.0, edt.rangeEndDb)
        assertEquals(-60.0 / edt.slopeDbPerSecond, edt.seconds, 1e-12)
        assertTrue(relErr(edt.seconds, 0.5) < 0.05, "edt ${edt.seconds}")
    }

    @Test
    fun `gain invariance in the linear unclipped case`() {
        val base = SyntheticImpulse.generate(SyntheticImpulse.Spec(noiseRmsDbfs = -75.0))
        val a = analyzer.analyze(base)
        for (gain in listOf(0.1, 0.5, 1.5)) { // stays below the -1 dBFS headroom warning
            val b = analyzer.analyze(base.scaled(gain))
            for (fc in bands) {
                val ta = a.band(fc).t20?.seconds
                val tb = b.band(fc).t20?.seconds
                assertEquals(a.band(fc).state, b.band(fc).state, "gain $gain band $fc")
                if (ta != null && tb != null) assertTrue(relErr(tb, ta) < 1e-4, "gain $gain band $fc: $ta vs $tb")
                assertEquals(a.band(fc).marginDb!!, b.band(fc).marginDb!!, 1e-3)
            }
        }
    }

    @Test
    fun `background noise reduces the margin until bands become invalid with a reason`() {
        // Moderate noise: still valid and accurate (noise-aware truncation keeps the fit unbiased).
        val moderate = analyzer.analyze(SyntheticImpulse.generate(SyntheticImpulse.Spec(noiseRmsDbfs = -62.0)))
        for (fc in listOf(500, 1000, 2000)) {
            val b = moderate.band(fc)
            assertTrue(b.marginDb!! >= 35.0, moderate.describe())
            val t20 = assertNotNull(b.t20, moderate.describe())
            assertTrue(relErr(t20.seconds, 0.5) < 0.05, "band $fc got ${t20.seconds}\n${moderate.describe()}")
        }
        // Heavy noise: not enough margin for T20 anywhere; numbers are withheld, reasons given.
        val noisy = analyzer.analyze(SyntheticImpulse.generate(SyntheticImpulse.Spec(noiseRmsDbfs = -30.0)))
        for (fc in bands) {
            val b = noisy.band(fc)
            assertEquals(QualityState.INVALID, b.state, noisy.describe())
            assertNull(b.t20)
            assertTrue(b.reasons.any { it.code == ReasonCode.INSUFFICIENT_SNR_MARGIN }, noisy.describe())
        }
    }

    @Test
    fun `untruncated noisy tail would bias the decay - truncation keeps it unbiased`() {
        // Long noisy tail after the decay; with ~40 dB margin a naive full-length Schroeder integral
        // bends the EDC upward. The truncated, compensated EDC must stay within 5 %.
        val spec = SyntheticImpulse.Spec(durationSeconds = 6.0, noiseRmsDbfs = -66.0)
        val r = analyzer.analyze(SyntheticImpulse.generate(spec))
        val b = r.band(1000)
        val t20 = assertNotNull(b.t20, r.describe())
        assertTrue(relErr(t20.seconds, 0.5) < 0.05, "got ${t20.seconds}")
        assertTrue(b.truncationSeconds!! < 2.0, "truncation at ${b.truncationSeconds}")
    }

    @Test
    fun `clipping invalidates the attempt`() {
        val sig = SyntheticImpulse.generate(SyntheticImpulse.Spec(peakAmplitude = 0.5))
        val clipped = PcmSignal(FloatArray(sig.size) { (sig.samples[it] * 4f).coerceIn(-1f, 1f) }, sig.sampleRate)
        val r = analyzer.analyze(clipped)
        assertEquals(QualityState.INVALID, r.state)
        assertTrue(r.reasons.any { it.code == ReasonCode.CLIPPING })
        r.bands.forEach { assertNull(it.t20); assertEquals(QualityState.INVALID, it.state) }
    }

    @Test
    fun `flat-topped waveform below full scale is detected`() {
        val sig = SyntheticImpulse.generate(SyntheticImpulse.Spec(peakAmplitude = 0.9))
        val limited = PcmSignal(FloatArray(sig.size) { sig.samples[it].coerceIn(-0.5f, 0.5f) }, sig.sampleRate)
        val r = analyzer.analyze(limited)
        assertTrue(r.reasons.any { it.code == ReasonCode.FLAT_TOP }, r.describe())
        assertEquals(QualityState.INVALID, r.state)
    }

    @Test
    fun `a second clap during the decay invalidates the attempt`() {
        val a = SyntheticImpulse.generate(SyntheticImpulse.Spec(durationSeconds = 3.0))
        val b = SyntheticImpulse.generate(SyntheticImpulse.Spec(durationSeconds = 3.0, seed = 9, peakAmplitude = 0.3))
        val offset = (0.6 * a.sampleRate).toInt()
        val pre = (0.75 * a.sampleRate).toInt()
        val mixed = FloatArray(a.size) { i ->
            val j = i - offset
            a.samples[i] + if (j in 0 until b.size) b.samples[j] - (if (j < pre) b.samples[j] else 0f) else 0f
        }
        val r = analyzer.analyze(PcmSignal(mixed, a.sampleRate))
        assertTrue(r.reasons.any { it.code == ReasonCode.ADDITIONAL_IMPULSE }, r.describe())
        assertEquals(QualityState.INVALID, r.state)
    }

    @Test
    fun `onset is found at the start of a slow decay whose largest sample comes later`() {
        // Regression: with T = 1.2 s the largest sample can occur ~0.2 s after the true start.
        for (seed in listOf(1L, 4L)) {
            val spec = SyntheticImpulse.Spec(decaySeconds = bands.associateWith { 1.2 }, durationSeconds = 4.35, seed = seed)
            val r = analyzer.analyze(SyntheticImpulse.generate(spec))
            assertEquals(0.75, r.onsetSeconds!!, 0.005, r.describe())
            assertTrue(r.reasons.none { it.code == ReasonCode.ADDITIONAL_IMPULSE }, r.describe())
        }
    }

    @Test
    fun `a louder second clap is caught`() {
        val quiet = SyntheticImpulse.generate(SyntheticImpulse.Spec(peakAmplitude = 0.15, durationSeconds = 3.5))
        val loud = SyntheticImpulse.generate(SyntheticImpulse.Spec(peakAmplitude = 0.5, durationSeconds = 3.5, seed = 3, noiseRmsDbfs = -200.0))
        val shift = (0.35 * quiet.sampleRate).toInt()
        val mixed = FloatArray(quiet.size) { i -> quiet.samples[i] + if (i - shift >= 0) loud.samples[i - shift] else 0f }
        val r = analyzer.analyze(PcmSignal(mixed, quiet.sampleRate))
        assertTrue(r.reasons.any { it.code == ReasonCode.ADDITIONAL_IMPULSE }, r.describe())
    }

    @Test
    fun `a loud event in the background segment invalidates the attempt`() {
        val sig = SyntheticImpulse.generate(SyntheticImpulse.Spec(preRollSeconds = 1.0))
        val s = sig.samples.copyOf()
        val at = (0.4 * sig.sampleRate).toInt()
        for (i in 0 until 200) s[at + i] += (0.3 * Math.sin(i * 0.7)).toFloat()
        val r = analyzer.analyze(PcmSignal(s, sig.sampleRate))
        assertTrue(r.reasons.any { it.code == ReasonCode.ADDITIONAL_IMPULSE }, r.describe())
    }

    @Test
    fun `short recordings are rejected with explicit reasons`() {
        val shortTail = SyntheticImpulse.generate(SyntheticImpulse.Spec(durationSeconds = 1.2))
        val r1 = analyzer.analyze(shortTail)
        assertTrue(r1.reasons.any { it.code == ReasonCode.INSUFFICIENT_TAIL }, r1.describe())
        r1.bands.forEach { assertNull(it.t20) }

        val noPreRoll = SyntheticImpulse.generate(SyntheticImpulse.Spec(preRollSeconds = 0.1))
        val r2 = analyzer.analyze(noPreRoll)
        assertTrue(r2.reasons.any { it.code == ReasonCode.INSUFFICIENT_PRE_ROLL }, r2.describe())
    }

    @Test
    fun `decay cut off by the end of the recording is not reported as T20`() {
        // Long decay in a short recording: noise floor is reached in time but the decay range is cut.
        val spec = SyntheticImpulse.Spec(decaySeconds = bands.associateWith { 2.5 }, durationSeconds = 1.9)
        val r = analyzer.analyze(SyntheticImpulse.generate(spec))
        for (fc in bands) {
            val b = r.band(fc)
            assertNull(b.t20, r.describe())
            assertTrue(
                b.reasons.any { it.code == ReasonCode.INSUFFICIENT_DECAY_RANGE || it.code == ReasonCode.INSUFFICIENT_TAIL },
                r.describe(),
            )
        }
    }

    @Test
    fun `double slope decay is flagged rather than silently reported`() {
        val spec = SyntheticImpulse.Spec(
            decaySeconds = bands.associateWith { 0.3 },
            lateDecay = bands.associateWith { SyntheticImpulse.LateDecay(1.2, -18.0) },
            durationSeconds = 4.0,
            noiseRmsDbfs = -100.0,
        )
        val r = analyzer.analyze(SyntheticImpulse.generate(spec))
        for (fc in listOf(500, 1000, 2000)) {
            val b = r.band(fc)
            assertTrue(
                b.reasons.any { it.code == ReasonCode.CURVED_DECAY || it.code == ReasonCode.NON_LINEAR_DECAY },
                r.describe(),
            )
            assertEquals(QualityState.QUESTIONABLE, b.state, r.describe())
        }
    }

    @Test
    fun `capture discontinuities and dropouts invalidate the attempt`() {
        val sig = SyntheticImpulse.generate(SyntheticImpulse.Spec())
        val r1 = analyzer.analyze(PcmSignal(sig.samples, sig.sampleRate, discontinuities = listOf(1234)))
        assertTrue(r1.reasons.any { it.code == ReasonCode.CAPTURE_DISCONTINUITY })
        assertEquals(QualityState.INVALID, r1.state)

        val s = sig.samples.copyOf()
        for (i in 60_000 until 60_400) s[i] = 0f
        val r2 = analyzer.analyze(PcmSignal(s, sig.sampleRate))
        assertTrue(r2.reasons.any { it.code == ReasonCode.DROPOUT }, r2.describe())
    }

    @Test
    fun `leading zeros from microphone start-up are not a dropout`() {
        // Seen on a real phone: the first 839 samples (17 ms) were exact zeros.
        val sig = SyntheticImpulse.generate(SyntheticImpulse.Spec())
        val s = sig.samples.copyOf()
        for (i in 0 until 839) s[i] = 0f
        val r = analyzer.analyze(PcmSignal(s, sig.sampleRate))
        assertTrue(r.reasons.none { it.code == ReasonCode.DROPOUT }, r.describe())
        assertEquals(QualityState.VALID, r.state, r.describe())
    }

    @Test
    fun `a peak with almost no headroom is questionable`() {
        val sig = SyntheticImpulse.generate(SyntheticImpulse.Spec(peakAmplitude = 0.95))
        val r = analyzer.analyze(sig)
        assertTrue(r.reasons.any { it.code == ReasonCode.NEAR_FULL_SCALE }, r.describe())
        assertEquals(QualityState.QUESTIONABLE, r.state)
        r.bands.forEach { assertTrue(it.state != QualityState.VALID) }
    }

    @Test
    fun `silence and noise without an impulse are rejected`() {
        val silent = analyzer.analyze(PcmSignal(FloatArray(48_000 * 3), 48_000))
        assertTrue(silent.reasons.any { it.code == ReasonCode.NO_IMPULSE_FOUND })
        val g = SyntheticImpulse.Gaussian(5)
        val noise = analyzer.analyze(PcmSignal(FloatArray(48_000 * 3) { (g.next() * 0.01).toFloat() }, 48_000))
        assertEquals(QualityState.INVALID, noise.state, noise.describe())
        noise.bands.forEach { assertNull(it.t20) }
    }

    @Test
    fun `analysis is deterministic`() {
        val sig = SyntheticImpulse.generate(SyntheticImpulse.Spec(seed = 42))
        val a = analyzer.analyze(sig)
        val b = analyzer.analyze(SyntheticImpulse.generate(SyntheticImpulse.Spec(seed = 42)))
        for (fc in bands) assertEquals(a.band(fc).t20?.seconds, b.band(fc).t20?.seconds)
    }

    @Test
    fun `results carry the configuration version`() {
        val r = analyzer.analyze(SyntheticImpulse.generate(SyntheticImpulse.Spec()))
        assertEquals(AnalysisConfiguration.VERSION, r.configuration.version)
        assertEquals(AnalysisConfiguration.ALGORITHM_ID, r.configuration.algorithmId)
    }

    @Test
    fun `44_1 kHz input works`() {
        val spec = SyntheticImpulse.Spec(sampleRate = 44_100, excitation = SyntheticImpulse.Excitation.DAMPED_TONES)
        val r = analyzer.analyze(SyntheticImpulse.generate(spec))
        for (fc in bands) assertTrue(relErr(assertNotNull(r.band(fc).t20, r.describe()).seconds, 0.5) < 0.05)
    }
}
