package se.klang.app

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.klang.app.audio.AudioCapabilities
import se.klang.app.audio.CapabilityReport
import se.klang.app.audio.MeasurementRecorder
import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.DecayAnalyzer
import se.klang.dsp.PcmSignal
import se.klang.dsp.Wav
import se.klang.dsp.WavEncoding
import se.klang.sessions.AnalysisRecord
import se.klang.sessions.AnalysisService
import se.klang.sessions.AudioConfiguration
import se.klang.sessions.AttemptKind
import se.klang.sessions.CaptureAttempt
import se.klang.sessions.CaptureEvent
import se.klang.sessions.Comparator
import se.klang.sessions.Comparison
import se.klang.sessions.Exporter
import se.klang.sessions.Records
import se.klang.sessions.Room
import se.klang.sessions.RoomConditions
import se.klang.sessions.Session
import se.klang.sessions.SessionKind
import se.klang.sessions.SessionStore
import java.io.File

/** Simple stack navigation without extra dependencies. */
sealed interface Screen {
    data object Home : Screen
    data object Capability : Screen
    data class RoomDetail(val roomId: String) : Screen
    data class SessionDetail(val sessionId: String) : Screen
    data class AttemptDetail(val sessionId: String, val attemptId: String) : Screen
    data class Compare(val roomId: String) : Screen
}

sealed interface CaptureUi {
    data object Idle : CaptureUi
    /** [phase]: QUIET (background pre-roll), CLAP (prompt), LISTEN (decay), BACKGROUND (background-only recording). */
    data class Recording(val phase: String, val elapsed: Double, val total: Double, val kind: AttemptKind) : CaptureUi
    data object Analyzing : CaptureUi
    data class Done(val attemptId: String) : CaptureUi
    data class Problem(val message: String) : CaptureUi
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = SessionStore(File(app.filesDir, "klang"))
    private val analysis = AnalysisService(store)
    private val recorder = MeasurementRecorder(app)
    val analysisConfig = AnalysisConfiguration()

    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = backStack.last()

    /** Bumped after every write so screens re-read from the store. */
    var revision by mutableIntStateOf(0)
        private set

    var capture by mutableStateOf<CaptureUi>(CaptureUi.Idle)
        private set

    var capabilities by mutableStateOf<CapabilityReport?>(null)
        private set

    fun navigate(s: Screen) { backStack += s }
    fun back(): Boolean {
        if (capture is CaptureUi.Recording) return true // do not leave mid-capture
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        capture = CaptureUi.Idle
        return true
    }

    private fun changed() { revision++ }

    // ---------------------------------------------------------------- data

    fun rooms(): List<Room> = store.rooms()
    fun room(id: String): Room? = store.room(id)
    fun sessions(roomId: String): List<Session> = store.sessions(roomId)
    fun session(id: String): Session? = store.session(id)
    fun attempts(sessionId: String): List<CaptureAttempt> = store.attempts(sessionId)

    fun createRoom(name: String, notes: String) { store.createRoom(name, notes); changed() }
    fun addPosition(roomId: String, name: String, notes: String) { store.addPosition(roomId, name, notes); changed() }
    fun createSession(roomId: String, name: String, kind: SessionKind, intervention: String, conditions: RoomConditions) {
        store.createSession(roomId, name, kind, intervention, conditions); changed()
    }
    fun deleteAttempt(a: CaptureAttempt) { store.deleteAttempt(a); changed() }

    fun refreshCapabilities() { capabilities = AudioCapabilities.probe(getApplication()) }

    /** Result of a feasibility test capture on the capability screen. Not stored in any session. */
    data class TestCapture(
        val samples: FloatArray,
        val sampleRate: Int,
        val configuration: AudioConfiguration,
        val events: List<CaptureEvent>,
        val analysis: AnalysisRecord,
    )

    var testCapture by mutableStateOf<TestCapture?>(null)
        private set

    /** Same flow as a real attempt (1.5 s quiet, clap, decay), analysed but not saved to a session. */
    fun runTestCapture() {
        if (capture is CaptureUi.Recording || capture is CaptureUi.Analyzing) return
        val total = 5.0
        capture = CaptureUi.Recording("QUIET", 0.0, total, AttemptKind.IMPULSE)
        viewModelScope.launch {
            val outcome = recorder.record(total) { t ->
                val phase = if (t < PRE_ROLL_SECONDS) "QUIET" else if (t < PRE_ROLL_SECONDS + 1.5) "CLAP" else "LISTEN"
                viewModelScope.launch(Dispatchers.Main) {
                    if (capture is CaptureUi.Recording) capture = CaptureUi.Recording(phase, t, total, AttemptKind.IMPULSE)
                }
            }
            capture = when (outcome) {
                is MeasurementRecorder.Outcome.Cancelled -> CaptureUi.Problem("Capture ${outcome.reason.lowercase()}.")
                is MeasurementRecorder.Outcome.Failed -> CaptureUi.Problem(outcome.message)
                is MeasurementRecorder.Outcome.Completed -> {
                    capture = CaptureUi.Analyzing
                    val record = withContext(Dispatchers.Default) {
                        val discontinuities = outcome.events.filter { it.type in DISCONTINUITY_TYPES }.map { it.atFrame.toInt() }
                        val result = DecayAnalyzer(analysisConfig).analyze(PcmSignal(outcome.samples, outcome.sampleRate, discontinuities))
                        Records.of(result, System.currentTimeMillis())
                    }
                    testCapture = TestCapture(outcome.samples, outcome.sampleRate, outcome.configuration, outcome.events, record)
                    CaptureUi.Idle
                }
            }
        }
    }

    /** Shares the test recording as a WAV, e.g. to analyse the same file in Room EQ Wizard. */
    suspend fun shareTestWav(): Intent? = withContext(Dispatchers.IO) {
        val t = testCapture ?: return@withContext null
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "klang-test-${System.currentTimeMillis()}.wav")
        file.outputStream().use { Wav.write(it, t.samples, t.sampleRate, WavEncoding.FLOAT_32) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ---------------------------------------------------------------- capture

    /**
     * One attempt. Impulse: 1.5 s quiet pre-roll, then "clap once", recording continues to 5 s so the
     * decay and a noise-only tail are captured. Background: 5 s of quiet.
     */
    fun record(sessionId: String, positionId: String, kind: AttemptKind) {
        if (capture is CaptureUi.Recording || capture is CaptureUi.Analyzing) return
        val total = 5.0
        capture = CaptureUi.Recording(if (kind == AttemptKind.BACKGROUND) "BACKGROUND" else "QUIET", 0.0, total, kind)
        viewModelScope.launch {
            val outcome = recorder.record(total) { t ->
                val phase = when {
                    kind == AttemptKind.BACKGROUND -> "BACKGROUND"
                    t < PRE_ROLL_SECONDS -> "QUIET"
                    t < PRE_ROLL_SECONDS + 1.5 -> "CLAP"
                    else -> "LISTEN"
                }
                // Hop to the main thread; the capture thread must not touch Compose state.
                viewModelScope.launch(Dispatchers.Main) {
                    if (capture is CaptureUi.Recording) capture = CaptureUi.Recording(phase, t, total, kind)
                }
            }
            when (outcome) {
                is MeasurementRecorder.Outcome.Cancelled -> capture = CaptureUi.Problem("Capture ${outcome.reason.lowercase()}; nothing was saved.")
                is MeasurementRecorder.Outcome.Failed -> capture = CaptureUi.Problem(outcome.message)
                is MeasurementRecorder.Outcome.Completed -> {
                    capture = CaptureUi.Analyzing
                    val saved = withContext(Dispatchers.IO) {
                        val bytes = Wav.toBytes(outcome.samples, outcome.sampleRate, WavEncoding.FLOAT_32)
                        store.addAttempt(sessionId, positionId, bytes.inputStream(), kind, outcome.configuration, outcome.events)
                    }
                    if (kind == AttemptKind.IMPULSE) {
                        withContext(Dispatchers.Default) { analysis.analyze(saved, analysisConfig) }
                    }
                    changed()
                    capture = CaptureUi.Done(saved.id)
                }
            }
        }
    }

    /** Called when the app leaves the foreground: never keep the microphone open in the background. */
    fun interruptCapture() {
        if (capture is CaptureUi.Recording) recorder.cancel("INTERRUPTED")
    }

    fun cancelCapture() {
        if (capture is CaptureUi.Recording) recorder.cancel("CANCELLED")
    }

    fun dismissCapture() { capture = CaptureUi.Idle }

    fun permissionDenied() {
        capture = CaptureUi.Problem("Microphone permission is needed to record a measurement. Nothing is recorded outside a measurement.")
    }

    fun reanalyze(sessionId: String) {
        viewModelScope.launch(Dispatchers.Default) {
            analysis.reanalyzeSession(sessionId, analysisConfig)
            withContext(Dispatchers.Main) { changed() }
        }
    }

    // ---------------------------------------------------------------- compare & export

    fun compare(roomId: String, baselineId: String, treatmentId: String, repeatId: String?): Comparison? {
        val room = store.room(roomId) ?: return null
        val b = store.session(baselineId) ?: return null
        val t = store.session(treatmentId) ?: return null
        val r = repeatId?.let { store.session(it) }
        return Comparator().compare(
            room, b, store.attempts(b.id), t, store.attempts(t.id),
            r, r?.let { store.attempts(it.id) }.orEmpty(), analysisConfig.version,
        )
    }

    /** Builds the export ZIP in app-private cache and returns a share intent (system share sheet). */
    suspend fun exportRoom(roomId: String, comparisons: List<Comparison>): Intent = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val exporter = Exporter(store, BuildConfig.VERSION_NAME)
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "klang-export-${System.currentTimeMillis()}.zip")
        file.outputStream().use { exporter.writeZip(exporter.document(roomId, comparisons), it) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        const val PRE_ROLL_SECONDS = 1.5
        val DISCONTINUITY_TYPES: Set<String> = AnalysisService.DISCONTINUITY_EVENTS
        fun describeEvent(e: CaptureEvent) = "${e.type} at frame ${e.atFrame}${if (e.detail.isNotEmpty()) ": ${e.detail}" else ""}"
    }
}
