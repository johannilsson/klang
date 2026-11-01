package se.klang.app

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.klang.app.audio.MeasurementRecorder
import se.klang.dsp.AnalysisConfiguration
import se.klang.sessions.AttemptKind
import se.klang.sessions.AudioConfiguration

/** The capture phase timeline, the end-of-capture mapping and the re-analysis loop of [AppViewModel]. */
class CaptureFlowTest {

    @Test
    fun countdownRunsBeforeTheMicrophoneOpens() {
        assertEquals(listOf(3, 2, 1), CaptureFlow.countdown())
        assertEquals(AppViewModel.COUNTDOWN_SECONDS, CaptureFlow.countdown().size)
    }

    @Test
    fun impulsePhasesFollowThePreRollThenClapThenListen() {
        val pre = AppViewModel.PRE_ROLL_SECONDS
        val clapEnd = pre + CaptureFlow.CLAP_PROMPT_SECONDS
        val at = { t: Double -> CaptureFlow.phase(AttemptKind.IMPULSE, t) }
        assertEquals("QUIET", at(0.0))
        assertEquals("QUIET", at(pre - 1e-6))
        assertEquals("CLAP", at(pre))
        assertEquals("CLAP", at(clapEnd - 1e-6))
        assertEquals("LISTEN", at(clapEnd))
        assertEquals("LISTEN", at(AppViewModel.RECORD_SECONDS))
    }

    @Test
    fun backgroundRecordingHasNoClapPrompt() {
        for (t in listOf(0.0, 1.0, 1.5, 2.0, 3.0, 5.0)) assertEquals("BACKGROUND", CaptureFlow.phase(AttemptKind.BACKGROUND, t))
    }

    @Test
    fun timelineLeavesTheAnalyserItsRequiredPreRollAndTail() {
        val c = AnalysisConfiguration()
        // A clap right at the prompt still has enough background before it ...
        assertTrue(AppViewModel.PRE_ROLL_SECONDS >= c.filterSettleSeconds + c.minPreRollSeconds + c.onsetGuardSeconds)
        // ... and a clap at the end of the prompt still leaves the decay and noise tail.
        assertTrue(AppViewModel.RECORD_SECONDS - (AppViewModel.PRE_ROLL_SECONDS + CaptureFlow.CLAP_PROMPT_SECONDS) >= c.minPostOnsetSeconds)
    }

    @Test
    fun clapPeakIgnoresThePreRollAndHoldsTheMaximumAfterThePrompt() {
        assertEquals(-120.0, CaptureFlow.clapPeakHold("QUIET", -120.0, -3.0), 0.0)
        assertEquals(-20.0, CaptureFlow.clapPeakHold("CLAP", -120.0, -20.0), 0.0)
        assertEquals(-20.0, CaptureFlow.clapPeakHold("LISTEN", -20.0, -60.0), 0.0)
        assertEquals(-10.0, CaptureFlow.clapPeakHold("LISTEN", -20.0, -10.0), 0.0)
    }

    @Test
    fun interruptedOrCancelledCaptureIsDiscardedWithAMessage() {
        assertEquals(
            CaptureUi.Problem("Capture interrupted; nothing was saved."),
            CaptureFlow.problemFor(MeasurementRecorder.Outcome.Cancelled("INTERRUPTED")),
        )
        assertEquals(
            CaptureUi.Problem("Capture cancelled; nothing was saved."),
            CaptureFlow.problemFor(MeasurementRecorder.Outcome.Cancelled("CANCELLED")),
        )
        assertEquals(
            CaptureUi.Problem("Capture failed (error -3)"),
            CaptureFlow.problemFor(MeasurementRecorder.Outcome.Failed("Capture failed (error -3)")),
        )
    }

    @Test
    fun completedCaptureGoesOnToAnalysis() {
        val config = AudioConfiguration(
            requestedSampleRate = 48_000, actualSampleRate = 48_000, encoding = "PCM_FLOAT", channelCount = 1,
            audioSource = "UNPROCESSED", unprocessedSupported = true, sourceFallback = false, inputDevice = null,
            deviceManufacturer = "test", deviceModel = "test", androidRelease = "16", androidSdkInt = 36,
        )
        assertNull(CaptureFlow.problemFor(MeasurementRecorder.Outcome.Completed(FloatArray(4), 48_000, config, emptyList())))
    }

    @Test
    fun oneFailingSessionDoesNotStopTheOthersAndIsReported() {
        val analysed = mutableListOf<String>()
        val failures = mutableListOf<String>()
        CaptureFlow.reanalyzeEach(listOf("a", "b", "c", "a"), failures, { if (it == "b") "Baseline" else null }) { id ->
            if (id == "b") throw IllegalStateException("corrupt WAV")
            analysed += id
        }
        assertEquals("every other session is analysed, each once", listOf("a", "c"), analysed)
        assertEquals(listOf("Baseline: corrupt WAV"), failures)
        assertEquals("Re-analysis failed for:\nBaseline: corrupt WAV", CaptureFlow.reanalysisMessage(failures))
    }

    @Test
    fun failureWithoutNameOrMessageFallsBackToIdAndExceptionType() {
        val failures = mutableListOf<String>()
        CaptureFlow.reanalyzeEach(listOf("s1", "s2"), failures, { null }) { throw IllegalArgumentException() }
        assertEquals(listOf("s1: IllegalArgumentException", "s2: IllegalArgumentException"), failures)
    }

    @Test
    fun noFailuresMeansNoError() {
        assertNull(CaptureFlow.reanalysisMessage(emptyList()))
    }

    @Test
    fun cancellationIsNotReportedAsAFailureButKeepsEarlierOnes() {
        val failures = mutableListOf<String>()
        val thrown = runCatching {
            CaptureFlow.reanalyzeEach(listOf("a", "b", "c"), failures, { it }) { id ->
                if (id == "a") error("broken")
                if (id == "b") throw CancellationException("view model cleared")
            }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(listOf("a: broken"), failures)
    }
}
