package se.klang.sessions

import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.DecayAnalyzer
import se.klang.dsp.PcmSignal
import se.klang.dsp.Wav

/**
 * Runs (or re-runs) the DSP on stored original recordings. A new analysis is appended; earlier
 * results and the recording itself are never replaced.
 */
class AnalysisService(private val store: SessionStore, private val clock: () -> Long = System::currentTimeMillis) {

    fun analyze(attempt: CaptureAttempt, config: AnalysisConfiguration = AnalysisConfiguration()): CaptureAttempt {
        val decoded = store.recordingFile(attempt).inputStream().use { Wav.read(it) }
        val discontinuities = attempt.captureEvents
            .filter { it.type in DISCONTINUITY_EVENTS }
            .map { it.atFrame.coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }
        val signal = PcmSignal(decoded.signal.samples, decoded.signal.sampleRate, discontinuities)
        val result = DecayAnalyzer(config).analyze(signal)
        return store.appendAnalysis(attempt, Records.of(result, clock()))
    }

    /** Re-analyses every impulse attempt in a session that has no result for [config]'s version. */
    fun reanalyzeSession(sessionId: String, config: AnalysisConfiguration = AnalysisConfiguration()): List<CaptureAttempt> =
        store.attempts(sessionId).filter { it.kind == AttemptKind.IMPULSE }.map {
            if (it.analysis(config.version) != null) it else analyze(it, config)
        }

    companion object {
        /** Capture events that break the sample timeline. */
        val DISCONTINUITY_EVENTS = setOf("OVERRUN", "SHORT_READ", "ROUTE_CHANGED", "INTERRUPTED", "SILENCED")
    }
}
