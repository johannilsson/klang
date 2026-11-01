package se.klang.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.klang.app.audio.AudioCapabilities
import se.klang.app.audio.CapabilityReport
import se.klang.app.audio.CaptureDiagnostics
import se.klang.app.audio.MeasurementRecorder
import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.ClapPeakTracker
import se.klang.dsp.DecayAnalyzer
import se.klang.dsp.Levels
import se.klang.dsp.PcmSignal
import se.klang.dsp.Wav
import se.klang.dsp.WavEncoding
import se.klang.dsp.WavFormatException
import se.klang.sessions.AnalysisRecord
import se.klang.sessions.AnalysisService
import se.klang.sessions.AttemptReport
import se.klang.sessions.AttemptReporter
import se.klang.sessions.AudioConfiguration
import se.klang.sessions.AttemptKind
import se.klang.sessions.CaptureAttempt
import se.klang.sessions.CaptureEvent
import se.klang.sessions.Comparator
import se.klang.sessions.Comparison
import se.klang.sessions.ComparisonText
import se.klang.sessions.Exporter
import se.klang.sessions.ImportException
import se.klang.sessions.Importer
import se.klang.sessions.ProfileImportException
import se.klang.sessions.ProfileSelection
import se.klang.sessions.Records
import se.klang.sessions.RecordingTimeline
import se.klang.sessions.ReferenceProfile
import se.klang.sessions.ReferenceProfileFile
import se.klang.sessions.Room
import se.klang.sessions.RoomConditions
import se.klang.sessions.RoomUse
import se.klang.sessions.Session
import se.klang.sessions.SessionKind
import se.klang.sessions.SessionStore
import se.klang.sessions.SessionSummary
import se.klang.sessions.Summarizer
import java.io.File
import java.io.IOException

/** Simple stack navigation without extra dependencies. */
sealed interface Screen {
    data object Home : Screen
    data object Capability : Screen
    data class RoomDetail(val roomId: String) : Screen
    data class SessionDetail(val sessionId: String) : Screen
    /** The detailed plain-language summary of a session: band diagrams, target, hints. */
    data class SessionSummary(val sessionId: String) : Screen
    data class AttemptDetail(val sessionId: String, val attemptId: String) : Screen
    data class Compare(val roomId: String) : Screen
}

/** Bottom sheets opened from buttons and the top-bar menu. Only one is open at a time. */
sealed interface Sheet {
    data object NewRoom : Sheet
    data class AddPosition(val roomId: String) : Sheet
    data class NewSession(val roomId: String) : Sheet
    data object Protocol : Sheet
    data object HowToClap : Sheet
    /** Editable conditions of a session. */
    data class Conditions(val sessionId: String) : Sheet
    data class EditRoom(val roomId: String) : Sheet
    data class EditPosition(val roomId: String, val positionId: String) : Sheet
    data class EditSession(val sessionId: String) : Sheet
    /** The room's reverberation target: use, suggested or own value, optional volume (#26, #27). */
    data class Target(val roomId: String) : Sheet
    /** Licences and attributions (OGL v3.0 for the BB93 targets). */
    data object About : Sheet
}

sealed interface CaptureUi {
    data object Idle : CaptureUi
    /** "Get ready" pause before the microphone opens, so the button tap is not in the background recording. */
    data class Countdown(val secondsLeft: Int, val kind: AttemptKind) : CaptureUi
    /**
     * [phase]: QUIET (background pre-roll), CLAP (prompt), LISTEN (decay), BACKGROUND (background-only recording).
     * [levelDbfs] is the peak of the last ~50 ms; [clapPeakDbfs] is the loudest level since the clap prompt.
     * Both are relative to digital full scale, not SPL.
     */
    data class Recording(
        val phase: String, val elapsed: Double, val total: Double, val kind: AttemptKind,
        val levelDbfs: Double = -120.0, val clapPeakDbfs: Double = -120.0,
    ) : CaptureUi
    /** Level practice: live metering only, nothing is saved or analysed. */
    data class Practice(
        val total: Double, val elapsed: Double, val levelDbfs: Double, val lastClapDbfs: Double, val claps: Int,
        val finished: Boolean = false,
    ) : CaptureUi
    data object Analyzing : CaptureUi
    data class Done(val attemptId: String) : CaptureUi
    data class Problem(val message: String) : CaptureUi
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = SessionStore(File(app.filesDir, "klang"))
    private val exportDir = File(app.cacheDir, "exports")
    private val analysis = AnalysisService(store)
    private val recorder = MeasurementRecorder(app)
    val analysisConfig = AnalysisConfiguration()

    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = backStack.last()

    /** Bumped after every write so screens re-read from the store. */
    var revision by mutableIntStateOf(0)
        private set

    var capture by mutableStateOf<CaptureUi>(CaptureUi.Idle)
        @VisibleForTesting internal set

    val isCapturing: Boolean
        get() = capture.let {
            it is CaptureUi.Countdown || it is CaptureUi.Recording || it is CaptureUi.Analyzing ||
                (it is CaptureUi.Practice && !it.finished)
        }

    var capabilities by mutableStateOf<CapabilityReport?>(null)
        private set

    var sheet by mutableStateOf<Sheet?>(null)

    fun navigate(s: Screen) { sheet = null; backStack += s }
    fun back(): Boolean {
        if (isCapturing) return true // do not leave mid-capture
        if (backStack.size <= 1) return false
        sheet = null
        backStack.removeAt(backStack.lastIndex)
        capture = CaptureUi.Idle
        return true
    }

    private fun changed() { revision++ }

    init {
        // Shared export ZIPs and test WAVs accumulate in the cache; keep only the newest, which a share may still be reading.
        viewModelScope.launch(Dispatchers.IO) { ExportCache.prune(exportDir) }
    }

    // ---------------------------------------------------------------- data

    fun rooms(): List<Room> = store.rooms()
    fun room(id: String): Room? = store.room(id)
    fun sessions(roomId: String): List<Session> = store.sessions(roomId)
    fun session(id: String): Session? = store.session(id)
    fun attempts(sessionId: String): List<CaptureAttempt> = store.attempts(sessionId)

    fun createRoom(name: String, notes: String): Room = store.createRoom(name, notes).also { changed() }
    fun addPosition(roomId: String, name: String, placementNotes: String, clapSpot: String) {
        store.addPosition(roomId, name, placementNotes, clapSpot); changed()
    }
    fun createSession(roomId: String, name: String, kind: SessionKind, intervention: String, conditions: RoomConditions): Session =
        store.createSession(roomId, name, kind, intervention, conditions).also { changed() }

    /** The protocol order: a baseline, then a repeat baseline, then treatments. Only a default; the user can pick. */
    fun nextSessionKind(roomId: String): SessionKind {
        val kinds = sessions(roomId).map { it.kind }.toSet()
        return when {
            SessionKind.BASELINE !in kinds -> SessionKind.BASELINE
            SessionKind.REPEAT_BASELINE !in kinds -> SessionKind.REPEAT_BASELINE
            else -> SessionKind.TREATMENT
        }
    }

    fun defaultSessionName(roomId: String, kind: SessionKind, label: String): String {
        val n = sessions(roomId).count { it.kind == kind }
        return if (n == 0) label else "$label ${n + 1}"
    }

    /** Conditions of the room's latest session, to prefill the next one (the protocol keeps them the same). */
    fun lastConditions(roomId: String): RoomConditions? = sessions(roomId).lastOrNull()?.conditions?.copy(sourcePosition = "")
    /**
     * Deletes one clap or background attempt with its recording. Blocked like every edit while a
     * capture or re-analysis runs: re-analysis rewrites attempt records and would bring a deleted one back.
     */
    fun deleteAttempt(a: CaptureAttempt): Boolean {
        if (editingBlocked) return false
        store.deleteAttempt(a); changed()
        return true
    }

    /**
     * True while something writes to the store in the background (capture and its analysis,
     * re-analysis, import). Edits and deletes are refused meanwhile, like the deletes of #18, so a
     * record is never rewritten from two places at once.
     */
    val editingBlocked: Boolean get() = isCapturing || reanalyzing || importing

    fun updateRoom(roomId: String, name: String, notes: String): Boolean = edit { store.updateRoom(roomId, name, notes) }
    fun updatePosition(roomId: String, positionId: String, name: String, placementNotes: String, clapSpot: String): Boolean =
        edit { store.updatePosition(roomId, positionId, name, placementNotes, clapSpot) }
    fun movePosition(roomId: String, positionId: String, offset: Int): Boolean = edit { store.movePosition(roomId, positionId, offset) }
    fun updateSession(sessionId: String, name: String, kind: SessionKind, intervention: String, conditions: RoomConditions): Boolean =
        edit { store.updateSession(sessionId, name, kind, intervention, conditions) }

    private inline fun edit(block: () -> Unit): Boolean {
        if (editingBlocked) return false
        block(); changed()
        return true
    }

    /** What deleting a position removes: its clap and background recordings, and the sessions they are in. */
    data class PositionDeleteImpact(val recordings: Int, val sessions: Int)

    fun positionDeleteImpact(roomId: String, positionId: String): PositionDeleteImpact {
        val bySession = store.positionAttempts(roomId, positionId)
        return PositionDeleteImpact(bySession.values.sumOf { it.size }, bySession.size)
    }

    /** Permanently deletes a position and its recordings in every session of the room, and closes their attempt screens. */
    fun deletePosition(roomId: String, positionId: String): Boolean {
        if (editingBlocked) return false
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                // Already gone (a repeated tap): nothing to do.
                if (store.room(roomId)?.positions?.any { it.id == positionId } != true) return@withContext emptySet()
                val ids = store.positionAttempts(roomId, positionId).values.flatten().map { it.id }.toSet()
                store.deletePosition(roomId, positionId)
                ids
            }
            backStack.removeAll { it is Screen.AttemptDetail && it.attemptId in removed }
            changed()
        }
        return true
    }

    /** Duration and levels of a recording, read from its WAV (dBFS, never SPL). Null when the file cannot be read. */
    data class RecordingLevels(val durationSeconds: Double, val sampleRate: Int, val rmsDbfs: Double, val peakDbfs: Double)

    suspend fun recordingLevels(a: CaptureAttempt): RecordingLevels? = withContext(Dispatchers.IO) {
        try {
            val signal = Wav.read(store.recordingFile(a).readBytes()).signal
            RecordingLevels(signal.durationSeconds, signal.sampleRate, Levels.rmsDbfs(signal.samples), Levels.peakDbfs(signal.samples))
        } catch (e: IOException) {
            null
        } catch (e: WavFormatException) {
            null
        }
    }

    /** Whether lists include archived rooms / sessions (toggled from the top-bar menu). */
    var showArchivedRooms by mutableStateOf(false)
    var showArchivedSessions by mutableStateOf(false)

    /** Archive hides a room or session from the lists and keeps every record and recording. */
    fun setRoomArchived(roomId: String, archived: Boolean) { store.setRoomArchived(roomId, archived); changed() }
    fun setSessionArchived(sessionId: String, archived: Boolean) { store.setSessionArchived(sessionId, archived); changed() }

    /** Permanently deletes a room with its sessions, attempts and recordings, then returns to Home. */
    fun deleteRoom(roomId: String) {
        if (editingBlocked) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteRoom(roomId) }
            backStack.retainAll { it == Screen.Home }
            if (backStack.isEmpty()) backStack += Screen.Home
            changed()
        }
    }

    /** Permanently deletes a session with its attempts and recordings, and leaves its screens. */
    fun deleteSession(sessionId: String) {
        if (editingBlocked) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteSession(sessionId) }
            backStack.removeAll { (it is Screen.SessionDetail && it.sessionId == sessionId) || (it is Screen.AttemptDetail && it.sessionId == sessionId) }
            changed()
        }
    }

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
        @VisibleForTesting internal set

    /** Same flow as a real attempt (countdown, 1.5 s quiet, clap, decay), analysed but not saved to a session. */
    fun runTestCapture() {
        startCapture(AttemptKind.IMPULSE) { outcome ->
            val record = withContext(Dispatchers.Default) {
                val discontinuities = CaptureDiagnostics.discontinuityFrames(outcome.events)
                val result = DecayAnalyzer(analysisConfig).analyze(PcmSignal(outcome.samples, outcome.sampleRate, discontinuities))
                Records.of(result, System.currentTimeMillis())
            }
            testCapture = TestCapture(outcome.samples, outcome.sampleRate, outcome.configuration, outcome.events, record)
            CaptureUi.Idle
        }
    }

    /** Shares the test recording as a WAV, e.g. to analyse the same file in Room EQ Wizard. */
    suspend fun shareTestWav(): Intent? = withContext(Dispatchers.IO) {
        val t = testCapture ?: return@withContext null
        val app = getApplication<Application>()
        val dir = exportDir.apply { mkdirs() }
        ExportCache.prune(dir)
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

    private var captureJob: Job? = null
    private var clapTracker = ClapPeakTracker()

    /**
     * Practice clap level: opens the microphone for [PRACTICE_SECONDS] and reports each clap's peak
     * (dBFS) with plain-language feedback, so the user can find a comfortable distance and strength
     * before recording. Nothing is saved or analysed.
     */
    fun startPractice() {
        if (isCapturing) return
        clapTracker = ClapPeakTracker()
        captureJob = viewModelScope.launch {
            capture = CaptureUi.Practice(PRACTICE_SECONDS, 0.0, -120.0, -120.0, 0)
            val outcome = recorder.record(PRACTICE_SECONDS) { t, peakDb ->
                viewModelScope.launch(Dispatchers.Main) {
                    val cur = capture as? CaptureUi.Practice ?: return@launch
                    var last = cur.lastClapDbfs
                    var claps = cur.claps
                    clapTracker.update(peakDb)?.let { last = it; claps++ }
                    capture = cur.copy(elapsed = t, levelDbfs = peakDb, lastClapDbfs = last, claps = claps)
                }
            }
            val cur = capture
            capture = when {
                outcome is MeasurementRecorder.Outcome.Failed -> CaptureUi.Problem(outcome.message)
                cur is CaptureUi.Practice && cur.claps > 0 -> cur.copy(finished = true)
                else -> CaptureUi.Idle
            }
        }
    }

    /**
     * One attempt: a 3 s "get ready" countdown (the microphone is closed), then 5 s of recording.
     * Impulse: 1.5 s quiet pre-roll, then "clap once", recording continues so the decay and a
     * noise-only tail are captured. Background: 5 s of quiet.
     */
    fun record(sessionId: String, positionId: String, kind: AttemptKind) {
        startCapture(kind) { outcome ->
            val saved = withContext(Dispatchers.IO) {
                val bytes = Wav.toBytes(outcome.samples, outcome.sampleRate, WavEncoding.FLOAT_32)
                store.addAttempt(sessionId, positionId, bytes.inputStream(), kind, outcome.configuration, outcome.events)
            }
            if (kind == AttemptKind.IMPULSE) {
                withContext(Dispatchers.Default) { analysis.analyze(saved, analysisConfig) }
            }
            changed()
            CaptureUi.Done(saved.id)
        }
    }

    private fun startCapture(kind: AttemptKind, onCompleted: suspend (MeasurementRecorder.Outcome.Completed) -> CaptureUi) {
        if (isCapturing) return
        captureJob = viewModelScope.launch {
            for (s in CaptureFlow.countdown()) {
                capture = CaptureUi.Countdown(s, kind)
                delay(1000)
            }
            val total = RECORD_SECONDS
            capture = CaptureUi.Recording(CaptureFlow.phase(kind, 0.0), 0.0, total, kind)
            val outcome = recorder.record(total) { t, peakDb ->
                val phase = CaptureFlow.phase(kind, t)
                // Hop to the main thread; the capture thread must not touch Compose state.
                viewModelScope.launch(Dispatchers.Main) {
                    val cur = capture
                    if (cur is CaptureUi.Recording) {
                        capture = CaptureUi.Recording(phase, t, total, kind, peakDb, CaptureFlow.clapPeakHold(phase, cur.clapPeakDbfs, peakDb))
                    }
                }
            }
            capture = CaptureFlow.problemFor(outcome) ?: run {
                capture = CaptureUi.Analyzing
                onCompleted(outcome as MeasurementRecorder.Outcome.Completed)
            }
        }
    }

    /** Called when the app leaves the foreground: never keep the microphone open in the background. */
    fun interruptCapture() {
        when (capture) {
            is CaptureUi.Countdown -> { captureJob?.cancel(); capture = CaptureUi.Idle }
            is CaptureUi.Recording -> recorder.cancel("INTERRUPTED")
            is CaptureUi.Practice -> recorder.cancel("INTERRUPTED")
            else -> Unit
        }
    }

    fun cancelCapture() {
        when (capture) {
            is CaptureUi.Countdown -> { captureJob?.cancel(); capture = CaptureUi.Idle }
            is CaptureUi.Recording -> recorder.cancel("CANCELLED")
            is CaptureUi.Practice -> recorder.cancel("CANCELLED")
            else -> Unit
        }
    }

    fun dismissCapture() { capture = CaptureUi.Idle }

    fun permissionDenied() {
        capture = CaptureUi.Problem("Microphone permission is needed to record a measurement. Nothing is recorded outside a measurement.")
    }

    /** True while [reanalyze] runs; screens disable their re-analyse buttons meanwhile. */
    var reanalyzing by mutableStateOf(false)
        private set

    /** Sessions whose last re-analysis failed, with the error; null when the last run succeeded. */
    var reanalysisError by mutableStateOf<String?>(null)
        private set

    fun reanalyze(sessionId: String) = reanalyze(listOf(sessionId))

    /**
     * Analyses every impulse attempt in [sessionIds] that has no result for the current algorithm
     * version. Appends results; recordings and older results are kept. Bumps [revision] when done,
     * so open comparisons are rebuilt.
     */
    fun reanalyze(sessionIds: List<String>) {
        if (reanalyzing || sessionIds.isEmpty()) return
        reanalyzing = true
        reanalysisError = null
        viewModelScope.launch(Dispatchers.Default) {
            // One failing session must not stop the others; failures are reported, not swallowed.
            val failures = mutableListOf<String>()
            try {
                CaptureFlow.reanalyzeEach(sessionIds, failures, { store.session(it)?.name }) { id ->
                    analysis.reanalyzeSession(id, analysisConfig)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    reanalyzing = false
                    reanalysisError = CaptureFlow.reanalysisMessage(failures)
                    changed()
                }
            }
        }
    }

    // ---------------------------------------------------------------- recording timeline and report

    private val timelines = java.util.concurrent.ConcurrentHashMap<String, RecordingTimeline>()

    /** The analysis a screen shows for an attempt: the current algorithm version's, else the latest. */
    fun analysisFor(a: CaptureAttempt): AnalysisRecord? = a.analysis(analysisConfig.version) ?: a.latestAnalysis()

    /**
     * The level-over-time of a recording, read from its WAV off the main thread and cached for the session of
     * the app (a re-analysis changes the key). Null when the file cannot be read.
     */
    suspend fun timeline(a: CaptureAttempt): RecordingTimeline? = withContext(Dispatchers.IO) {
        val analysis = analysisFor(a)
        val key = "${a.id}:${a.analyses.size}"
        timelines[key]?.let { return@withContext it }
        try {
            val signal = Wav.read(store.recordingFile(a).readBytes()).signal
            RecordingTimeline.of(signal.samples, signal.sampleRate, analysis).also { timelines[key] = it }
        } catch (e: IOException) {
            null
        } catch (e: WavFormatException) {
            null
        }
    }

    /** What to say about one recording (verdict, grouped findings), from its stored analysis. */
    fun report(a: CaptureAttempt, timeline: RecordingTimeline? = null): AttemptReport =
        AttemptReporter.report(analysisFor(a), a.captureEvents, timeline)

    // ---------------------------------------------------------------- summaries & targets (#26, #27)

    private val summarizer = Summarizer()

    /** Plain-language summary of a session against the room's target. Pure text rules, no I/O beyond reading the store. */
    fun sessionSummary(sessionId: String): SessionSummary? {
        val s = store.session(sessionId) ?: return null
        val room = store.room(s.roomId) ?: return null
        return summarizer.session(room, s, store.attempts(sessionId), analysisConfig.version)
    }

    fun comparisonText(roomId: String, c: Comparison): ComparisonText? {
        val room = store.room(roomId) ?: return null
        return summarizer.comparison(c, room, store.sessions(roomId))
    }

    fun importedProfiles(): List<ReferenceProfile> = store.importedProfiles()

    /**
     * Saves the room's use, volume and target. [ownSeconds] (when the user chose "my own value"; with [ownLowerSeconds] a range)
     * becomes a new version of the room's "your target" profile if the value changed.
     */
    fun saveRoomTarget(roomId: String, use: RoomUse?, volumeCubicMetres: Double?, selected: ReferenceProfile?, ownSeconds: Double?, ownLowerSeconds: Double? = null): Boolean = edit {
        val profile = ownSeconds?.let { store.userProfile(roomId, it, ownLowerSeconds) } ?: selected
        store.updateRoomTarget(roomId, use, volumeCubicMetres, profile?.let(ProfileSelection::of))
    }

    /** Result of importing a reference profile file, shown in the target sheet. */
    var profileImportMessage by mutableStateOf<String?>(null)

    /** Reads a `klang.reference-profile` file chosen in the system file picker. Nothing is stored on any error. */
    fun importProfile(uri: Uri) {
        viewModelScope.launch {
            profileImportMessage = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                try {
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "profile.json"
                    val bytes = app.contentResolver.openInputStream(uri)?.use { readAtMost(it, ReferenceProfileFile.MAX_BYTES + 1) }
                        ?: return@withContext "Could not open the selected file."
                    val r = store.importProfile(bytes, name)
                    if (r.alreadyPresent) "“${r.profile.name}” (version ${r.profile.version}) was already imported; nothing changed."
                    else "Imported “${r.profile.name}” (version ${r.profile.version}). Select it below to use it."
                } catch (e: ProfileImportException) {
                    "${e.message} Nothing was imported."
                } catch (e: SecurityException) {
                    "No permission to read the selected file. Nothing was imported."
                } catch (e: IOException) {
                    "Could not read the file: ${e.message}. Nothing was imported."
                }
            }
            changed()
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
        val exporter = Exporter(store, BuildConfig.VERSION_NAME, analysisConfig.version)
        val dir = exportDir.apply { mkdirs() }
        ExportCache.prune(dir)
        val file = File(dir, "klang-export-${System.currentTimeMillis()}.zip")
        file.outputStream().use { exporter.writeZip(exporter.document(roomId, comparisons), it) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Outcome of an import, shown until dismissed. */
    data class ImportOutcome(val succeeded: Boolean, val message: String)

    /** True while an import runs; [importOutcome] holds the result until dismissed. */
    var importing by mutableStateOf(false)
        private set
    var importOutcome by mutableStateOf<ImportOutcome?>(null)
        private set

    /**
     * Imports a `klang-export-*.zip` picked with the system file picker. Runs off the main thread;
     * never overwrites an existing recording or analysis (see docs/export-format.md).
     */
    fun importExport(uri: Uri) {
        if (importing || isCapturing) return
        importing = true
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { ImportOutcome(true, Importer(store).import(it).summary()) }
                        ?: ImportOutcome(false, "Could not open the selected file.")
                } catch (e: ImportException) {
                    ImportOutcome(false, "${e.message} Nothing was imported.")
                } catch (e: SecurityException) {
                    ImportOutcome(false, "No permission to read the selected file. Nothing was imported.")
                } catch (e: IOException) {
                    ImportOutcome(false, "The import stopped: ${e.message}. $PARTIAL_IMPORT")
                } catch (e: IllegalStateException) {
                    ImportOutcome(false, "The import stopped: ${e.message}. $PARTIAL_IMPORT")
                }
            }
            importing = false
            importOutcome = outcome
            changed()
        }
    }

    fun dismissImportOutcome() { importOutcome = null }

    companion object {
        /** Reads up to [limit] bytes (InputStream.readNBytes needs API 33). */
        internal fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        const val PRE_ROLL_SECONDS = 1.5
        const val COUNTDOWN_SECONDS = 3
        const val RECORD_SECONDS = 5.0
        const val PRACTICE_SECONDS = 30.0
        private const val PARTIAL_IMPORT =
            "Recordings imported so far are complete and nothing existing was replaced; importing the same file again finishes the rest."
        val DISCONTINUITY_TYPES: Set<String> = AnalysisService.DISCONTINUITY_EVENTS
        fun describeEvent(e: CaptureEvent) = "${e.type} at frame ${e.atFrame}${if (e.detail.isNotEmpty()) ": ${e.detail}" else ""}"
    }
}
