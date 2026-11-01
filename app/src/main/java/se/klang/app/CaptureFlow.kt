package se.klang.app

import kotlinx.coroutines.CancellationException
import se.klang.app.audio.MeasurementRecorder
import se.klang.sessions.AttemptKind
import kotlin.math.max

/** Pure parts of the capture flow in [AppViewModel], kept free of Android and Compose for unit tests. */
internal object CaptureFlow {
    /** The "get ready" countdown shown before the microphone opens, in seconds left. */
    fun countdown(seconds: Int = AppViewModel.COUNTDOWN_SECONDS): List<Int> = (seconds downTo 1).toList()

    /**
     * Prompt phase at [elapsedSeconds] into the recording: QUIET during the background pre-roll
     * ([AppViewModel.PRE_ROLL_SECONDS]), CLAP for the next 1.5 s, then LISTEN for the decay.
     * Background recordings stay BACKGROUND throughout.
     */
    fun phase(kind: AttemptKind, elapsedSeconds: Double): String = when {
        kind == AttemptKind.BACKGROUND -> "BACKGROUND"
        elapsedSeconds < AppViewModel.PRE_ROLL_SECONDS -> "QUIET"
        elapsedSeconds < AppViewModel.PRE_ROLL_SECONDS + CLAP_PROMPT_SECONDS -> "CLAP"
        else -> "LISTEN"
    }

    /** The clap peak held since the prompt: sounds during the quiet pre-roll do not count. */
    fun clapPeakHold(phase: String, heldDbfs: Double, peakDbfs: Double): Double =
        if (phase == "QUIET") heldDbfs else max(heldDbfs, peakDbfs)

    /**
     * What to show when a capture ends without a recording. Cancelled and interrupted captures
     * (e.g. the app left the foreground) and failures are discarded: nothing is saved or analysed.
     * Returns null for a completed capture, which goes on to analysis.
     */
    fun problemFor(outcome: MeasurementRecorder.Outcome): CaptureUi.Problem? = when (outcome) {
        is MeasurementRecorder.Outcome.Cancelled -> CaptureUi.Problem("Capture ${outcome.reason.lowercase()}; nothing was saved.")
        is MeasurementRecorder.Outcome.Failed -> CaptureUi.Problem(outcome.message)
        is MeasurementRecorder.Outcome.Completed -> null
    }

    /**
     * Runs [analyze] for every distinct session id. One failing session does not stop the others;
     * each failure is added to [failures] as "name: message" ([nameOf] null: the id is used), as it
     * happens, so failures before a cancellation are still reported.
     */
    fun reanalyzeEach(
        sessionIds: List<String>, failures: MutableList<String>,
        nameOf: (String) -> String?, analyze: (String) -> Unit,
    ) {
        sessionIds.distinct().forEach { id ->
            try {
                analyze(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "${nameOf(id) ?: id}: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    /** The message for [AppViewModel.reanalysisError]; null when nothing failed. */
    fun reanalysisMessage(failures: List<String>): String? =
        failures.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "Re-analysis failed for:\n")

    const val CLAP_PROMPT_SECONDS = 1.5
}
