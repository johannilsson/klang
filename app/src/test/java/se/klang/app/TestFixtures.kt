package se.klang.app

import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.QualityState
import se.klang.dsp.ReasonCode
import se.klang.dsp.Wav
import se.klang.dsp.WavEncoding
import se.klang.sessions.AnalysisRecord
import se.klang.sessions.BandRecord
import se.klang.sessions.Fit
import se.klang.sessions.Reason
import se.klang.sessions.Records
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln

/** Fixtures for app tests. Engineering checks only: none of these values are real-room results. */
internal object TestFixtures {
    const val RATE = 48_000

    /** 5 s: 1.5 s of low noise, then an exponentially decaying noise burst (T60 [t60] s). */
    fun clap(t60: Double = 0.5, seed: Long = 15): FloatArray {
        val rnd = Random(seed)
        val decayPerSecond = 3.0 * ln(10.0) / t60
        return FloatArray(5 * RATE) { i ->
            val t = (i - 3 * RATE / 2).toDouble() / RATE
            val burst = if (t >= 0) 0.1 * exp(-decayPerSecond * t) * rnd.nextGaussian() else 0.0
            (1e-4 * rnd.nextGaussian() + burst).toFloat()
        }
    }

    fun clapWav(): ByteArray = Wav.toBytes(clap(), RATE, WavEncoding.FLOAT_32)

    fun fit(seconds: Double) = Fit(seconds, -60.0 / seconds, 0.0, -5.0, -25.0, 0.05, 0.4, 0.99, 1.0, 0.1)

    /**
     * A hand-built record: 1000 Hz valid with T20 0.62 s; 500 Hz invalid with a reason and, as an
     * imported or older record might, a stale 0.87 s fit that must never be shown.
     */
    fun recordWithInvalidBand(): AnalysisRecord {
        val c = AnalysisConfiguration()
        return AnalysisRecord(
            algorithmId = c.algorithmId,
            algorithmVersion = c.version,
            configuration = Records.configuration(c),
            analyzedAtEpochMs = 0,
            state = QualityState.QUESTIONABLE,
            reasons = emptyList(),
            sampleRate = RATE,
            durationSeconds = 5.0,
            onsetSeconds = 1.5,
            peakDbfs = -12.0,
            backgroundRmsDbfs = -70.0,
            bands = listOf(
                BandRecord(
                    500, false, QualityState.INVALID,
                    listOf(Reason(ReasonCode.INSUFFICIENT_SNR_MARGIN, QualityState.INVALID, INVALID_REASON)),
                    t20 = fit(0.87),
                ),
                BandRecord(1000, false, QualityState.VALID, emptyList(), t20 = fit(0.62)),
            ),
        )
    }

    const val INVALID_REASON = "Decay is only 20 dB above the background in this band (needs 35)"
}
