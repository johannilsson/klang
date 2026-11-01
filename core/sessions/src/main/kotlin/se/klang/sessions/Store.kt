package se.klang.sessions

import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * File-based persistence in an app-private directory. Human-readable JSON, one file per entity,
 * written atomically (temp file + rename) so a crash never leaves a half-written record.
 *
 * Layout under [root]:
 * ```
 * rooms/<roomId>.json
 * sessions/<sessionId>/session.json
 * sessions/<sessionId>/attempts/<attemptId>.json
 * sessions/<sessionId>/recordings/<attemptId>.wav    (original, never overwritten)
 * ```
 *
 * Descriptive fields (names, notes, conditions) can be edited. Recordings and analysis results are
 * never edited: they can only be deleted, together with what they belong to. Deletion is always an
 * explicit user action, never automatic.
 */
class SessionStore(val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    init { root.mkdirs() }

    // ---- Rooms ----
    fun createRoom(name: String, notes: String = ""): Room =
        Room(newId(), name, notes, clock()).also(::saveRoom)

    fun saveRoom(room: Room) = writeAtomic(roomFile(room.id), json.encodeToString(Room.serializer(), room))

    fun room(id: String): Room? = roomFile(id).takeIf { it.exists() }?.let { json.decodeFromString(Room.serializer(), it.readText()) }

    fun rooms(): List<Room> = File(root, "rooms").listFiles { f -> f.extension == "json" }.orEmpty()
        .map { json.decodeFromString(Room.serializer(), it.readText()) }.sortedBy { it.createdAtEpochMs }

    fun addPosition(roomId: String, name: String, placementNotes: String = "", clapSpot: String = ""): MeasurementPosition {
        val room = requireNotNull(room(roomId)) { "No room $roomId" }
        val p = MeasurementPosition(newId(), name, placementNotes, clapSpot)
        saveRoom(room.copy(positions = room.positions + p))
        return p
    }

    /** Edits a room's name and notes. Positions and sessions are untouched. */
    fun updateRoom(roomId: String, name: String, notes: String): Room {
        val room = requireNotNull(room(roomId)) { "No room $roomId" }
        return room.copy(name = name, notes = notes).also(::saveRoom)
    }

    /** Edits a position's descriptive fields. Its id, and so its recordings, stay the same. */
    fun updatePosition(roomId: String, positionId: String, name: String, placementNotes: String, clapSpot: String): MeasurementPosition {
        val room = requireNotNull(room(roomId)) { "No room $roomId" }
        val old = requireNotNull(room.positions.firstOrNull { it.id == positionId }) { "No position $positionId" }
        val p = old.copy(name = name, placementNotes = placementNotes, clapSpot = clapSpot)
        saveRoom(room.copy(positions = room.positions.map { if (it.id == positionId) p else it }))
        return p
    }

    /** Recordings made at a position, across all of the room's sessions. */
    fun attemptsAtPosition(roomId: String, positionId: String): List<CaptureAttempt> =
        sessions(roomId).flatMap { attempts(it.id) }.filter { it.positionId == positionId }

    /**
     * Removes a position and every recording made at it in any of the room's sessions. Recordings are
     * deleted before the position, so an interruption never leaves recordings at an unknown position.
     */
    fun deletePosition(roomId: String, positionId: String) {
        attemptsAtPosition(roomId, positionId).forEach(::deleteAttempt)
        val room = room(roomId) ?: return
        saveRoom(room.copy(positions = room.positions.filterNot { it.id == positionId }))
    }

    /** Removes a room with all its sessions, recordings and analyses. */
    fun deleteRoom(roomId: String) {
        sessions(roomId).forEach { deleteSession(it.id) }
        roomFile(roomId).delete()
    }

    // ---- Sessions ----
    fun createSession(
        roomId: String, name: String, kind: SessionKind,
        intervention: String = "", conditions: RoomConditions = RoomConditions(),
    ): Session {
        requireNotNull(room(roomId)) { "No room $roomId" }
        return Session(newId(), roomId, name, kind, intervention, conditions, clock()).also(::saveSession)
    }

    fun saveSession(s: Session) = writeAtomic(File(sessionDir(s.id), "session.json"), json.encodeToString(Session.serializer(), s))

    fun session(id: String): Session? = File(sessionDir(id), "session.json").takeIf { it.exists() }
        ?.let { json.decodeFromString(Session.serializer(), it.readText()) }

    /** Edits a session's descriptive fields. Its recordings and their analyses are untouched. */
    fun updateSession(
        sessionId: String, name: String, kind: SessionKind, intervention: String, conditions: RoomConditions,
    ): Session {
        val s = requireNotNull(session(sessionId)) { "No session $sessionId" }
        return s.copy(name = name, kind = kind, intervention = intervention, conditions = conditions).also(::saveSession)
    }

    /**
     * Removes a session with all its recordings and analyses. session.json goes first, so an
     * interrupted deletion leaves no half-listed session behind; the rest of the directory follows.
     */
    fun deleteSession(sessionId: String) {
        val dir = sessionDir(sessionId)
        File(dir, "session.json").delete()
        dir.deleteRecursively()
    }

    fun sessions(roomId: String? = null): List<Session> = File(root, "sessions").listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { session(it.name) }.filter { roomId == null || it.roomId == roomId }.sortedBy { it.createdAtEpochMs }

    // ---- Attempts ----
    /**
     * Stores a new attempt. The WAV bytes are copied into the store first; the attempt record is
     * written only after the recording is safely on disk.
     */
    fun addAttempt(
        sessionId: String, positionId: String, wav: InputStream,
        kind: AttemptKind = AttemptKind.IMPULSE,
        audioConfiguration: AudioConfiguration? = null,
        captureEvents: List<CaptureEvent> = emptyList(),
        note: String = "",
    ): CaptureAttempt {
        requireNotNull(session(sessionId)) { "No session $sessionId" }
        val id = newId()
        val rel = "sessions/$sessionId/recordings/$id.wav"
        val file = File(root, rel)
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "$id.wav.tmp")
        tmp.outputStream().use { wav.copyTo(it) }
        check(tmp.renameTo(file)) { "Could not store recording" }
        val seq = attempts(sessionId).count { it.positionId == positionId && it.kind == kind } + 1
        val a = CaptureAttempt(id, sessionId, positionId, kind, seq, clock(), rel, audioConfiguration, captureEvents, emptyList(), note)
        saveAttempt(a)
        return a
    }

    fun saveAttempt(a: CaptureAttempt) =
        writeAtomic(File(sessionDir(a.sessionId), "attempts/${a.id}.json"), json.encodeToString(CaptureAttempt.serializer(), a))

    fun attempts(sessionId: String): List<CaptureAttempt> = File(sessionDir(sessionId), "attempts")
        .listFiles { f -> f.extension == "json" }.orEmpty()
        .map { json.decodeFromString(CaptureAttempt.serializer(), it.readText()) }
        .sortedWith(compareBy({ it.createdAtEpochMs }, { it.sequence }))

    fun recordingFile(a: CaptureAttempt): File = File(root, a.recordingPath)

    /** Optional deletion of an attempt (e.g. a rejected one) and its recording. Never automatic. */
    fun deleteAttempt(a: CaptureAttempt) {
        File(sessionDir(a.sessionId), "attempts/${a.id}.json").delete()
        recordingFile(a).delete()
    }

    /** Edits an attempt's free-text note. The recording and its analyses are never edited. */
    fun updateAttemptNote(a: CaptureAttempt, note: String): CaptureAttempt = a.copy(note = note).also(::saveAttempt)

    fun appendAnalysis(a: CaptureAttempt, record: AnalysisRecord): CaptureAttempt =
        a.copy(analyses = a.analyses + record).also(::saveAttempt)

    // ---- internals ----
    private fun roomFile(id: String) = File(root, "rooms/${safe(id)}.json")
    private fun sessionDir(id: String) = File(root, "sessions/${safe(id)}")

    private fun writeAtomic(target: File, text: String) {
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not write $target" }
        }
    }

    private fun safe(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid id" }
        return id
    }

    private fun newId() = UUID.randomUUID().toString()
}
