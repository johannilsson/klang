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
 * trash/                                             (tombstones of deletions in progress)
 * import-staging/                                    (WAVs unpacked by an import in progress)
 * ```
 *
 * Metadata edits ([updateRoom], [updatePosition], [movePosition], [updateSession]) rewrite the
 * record and set its `updatedAtEpochMs`; recordings and analyses are never edited.
 *
 * Archiving ([setRoomArchived], [setSessionArchived]) only sets a flag: lists in the app hide
 * archived items, and every record and recording is kept. Deleting ([deleteSession], [deleteRoom],
 * [deletePosition], [deleteAttempt]) is a separate, explicit step that removes recordings permanently. A session directory is first
 * renamed into `trash/` (one atomic rename), so a crash mid-delete leaves either the whole session
 * or none of it; leftovers in `trash/` are removed when the store is next opened.
 */
class SessionStore(val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    init {
        root.mkdirs()
        // Finish deletions interrupted by a crash: everything in trash/ was already unlinked from the store.
        File(root, TRASH).listFiles()?.forEach { it.deleteRecursively() }
        File(root, STAGING).listFiles()?.forEach { it.deleteRecursively() }
    }

    // ---- Rooms ----
    fun createRoom(name: String, notes: String = ""): Room =
        Room(newId(), name, notes, clock()).also(::saveRoom)

    fun saveRoom(room: Room) = writeAtomic(roomFile(room.id), json.encodeToString(Room.serializer(), room))

    fun room(id: String): Room? = roomFile(id).takeIf { it.exists() }?.let { json.decodeFromString(Room.serializer(), it.readText()) }

    fun rooms(): List<Room> = File(root, "rooms").listFiles { f -> f.extension == "json" }.orEmpty()
        .map { json.decodeFromString(Room.serializer(), it.readText()) }.sortedWith(compareBy({ it.createdAtEpochMs }, { it.id }))

    fun addPosition(roomId: String, name: String, placementNotes: String = "", clapSpot: String = ""): MeasurementPosition {
        val room = requireNotNull(room(roomId)) { "No room $roomId" }
        val p = MeasurementPosition(newId(), name, placementNotes, clapSpot)
        saveRoom(room.copy(positions = room.positions + p))
        return p
    }

    /** Renames a room or changes its notes. Sets [Room.updatedAtEpochMs]. */
    fun updateRoom(roomId: String, name: String, notes: String): Room {
        require(name.isNotBlank()) { "A room needs a name" }
        val room = requireNotNull(room(roomId)) { "No room $roomId" }
        return room.copy(name = name, notes = notes, updatedAtEpochMs = clock()).also(::saveRoom)
    }

    /**
     * Edits a position in place: there is no per-session copy, so every session of the room, older
     * ones included, shows the new name, placement and clap spot. Recordings are not touched.
     */
    fun updatePosition(roomId: String, positionId: String, name: String, placementNotes: String, clapSpot: String): MeasurementPosition {
        require(name.isNotBlank()) { "A position needs a name" }
        val room = requireNotNull(room(roomId)) { "No room $roomId" }
        val old = requireNotNull(room.positions.firstOrNull { it.id == positionId }) { "No position $positionId in room $roomId" }
        val p = old.copy(name = name, placementNotes = placementNotes, clapSpot = clapSpot, updatedAtEpochMs = clock())
        saveRoom(room.copy(positions = room.positions.map { if (it.id == positionId) p else it }))
        return p
    }

    /**
     * Moves a position [offset] places in the room's order (negative = earlier), clamped to the list.
     * Only the display order changes; comparisons match positions by id.
     */
    fun movePosition(roomId: String, positionId: String, offset: Int): Room {
        val room = requireNotNull(room(roomId)) { "No room $roomId" }
        val from = room.positions.indexOfFirst { it.id == positionId }
        require(from >= 0) { "No position $positionId in room $roomId" }
        val to = (from + offset).coerceIn(0, room.positions.lastIndex)
        if (to == from) return room
        val list = room.positions.toMutableList().apply { add(to, removeAt(from)) }
        return room.copy(positions = list, updatedAtEpochMs = clock()).also(::saveRoom)
    }

    /** Clap and background attempts of a position, per session of its room that has any (what a delete removes). */
    fun positionAttempts(roomId: String, positionId: String): Map<Session, List<CaptureAttempt>> =
        sessions(roomId).associateWith { s -> attempts(s.id).filter { it.positionId == positionId } }.filterValues { it.isNotEmpty() }

    /**
     * Permanently deletes a position and, in every session of its room (archived ones included), its
     * clap and background attempts with their analyses and recordings.
     *
     * Crash safety follows [deleteSession]: each attempt record is moved into `trash/` before its
     * recording is removed ([deleteAttempt]), so no attempt ever points at a missing WAV, and the
     * position leaves the room record last. An interrupted delete leaves a loadable room in which the
     * position still exists with fewer recordings (at worst one WAV no attempt refers to); running the
     * delete again finishes it.
     */
    fun deletePosition(roomId: String, positionId: String): Room {
        val room = requireNotNull(room(roomId)) { "No room $roomId" }
        require(room.positions.any { it.id == positionId }) { "No position $positionId in room $roomId" }
        positionAttempts(roomId, positionId).values.flatten().forEach(::deleteAttempt)
        return room.copy(positions = room.positions.filterNot { it.id == positionId }, updatedAtEpochMs = clock()).also(::saveRoom)
    }

    fun setRoomArchived(roomId: String, archived: Boolean): Room {
        val room = requireNotNull(room(roomId)) { "No room $roomId" }
        return room.copy(archived = archived).also(::saveRoom)
    }

    /**
     * Permanently deletes a room with all its sessions, attempts, analyses and recordings. Sessions
     * go first and the room record last, so an interrupted delete leaves a loadable room with fewer
     * sessions, never sessions or attempts whose files are missing.
     */
    fun deleteRoom(roomId: String) {
        sessions(roomId).forEach { deleteSession(it.id) }
        val file = roomFile(roomId)
        if (file.exists()) check(file.delete()) { "Could not delete room $roomId" }
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

    fun sessions(roomId: String? = null): List<Session> = File(root, "sessions").listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { session(it.name) }.filter { roomId == null || it.roomId == roomId }
        .sortedWith(compareBy({ it.createdAtEpochMs }, { it.id }))

    /**
     * Edits a session's metadata. Changing [kind] changes how Compare uses the session (baseline,
     * repeat baseline or treatment). Recordings and analyses are not touched. Sets [Session.updatedAtEpochMs].
     */
    fun updateSession(sessionId: String, name: String, kind: SessionKind, intervention: String, conditions: RoomConditions): Session {
        require(name.isNotBlank()) { "A session needs a name" }
        val s = requireNotNull(session(sessionId)) { "No session $sessionId" }
        return s.copy(name = name, kind = kind, intervention = intervention, conditions = conditions, updatedAtEpochMs = clock())
            .also(::saveSession)
    }

    fun setSessionArchived(sessionId: String, archived: Boolean): Session {
        val s = requireNotNull(session(sessionId)) { "No session $sessionId" }
        return s.copy(archived = archived).also(::saveSession)
    }

    /**
     * Permanently deletes a session with its attempts, analyses and recordings. The session
     * directory is renamed to a tombstone in `trash/` first, so the store never sees half a session.
     */
    fun deleteSession(sessionId: String) {
        val dir = sessionDir(sessionId)
        if (!dir.exists()) return
        val tomb = File(root, "$TRASH/session-$sessionId-${newId()}")
        tomb.parentFile.mkdirs()
        if (dir.renameTo(tomb)) {
            tomb.deleteRecursively()
            return
        }
        // Rename not possible: drop the session record first so the remainder is never loaded as a session.
        val meta = File(dir, "session.json")
        check(!meta.exists() || meta.delete()) { "Could not delete session $sessionId" }
        dir.deleteRecursively()
    }

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
        val rel = recordingPath(sessionId, id)
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
        .sortedWith(compareBy({ it.createdAtEpochMs }, { it.sequence }, { it.id }))

    fun recordingFile(a: CaptureAttempt): File = File(root, a.recordingPath)

    /** Store-relative path where the original recording of an attempt lives. */
    fun recordingPath(sessionId: String, attemptId: String): String =
        "sessions/${safe(sessionId)}/recordings/${safe(attemptId)}.wav"

    /**
     * Moves [source] into the store as the recording at [relPath] (from [recordingPath]). Never
     * replaces an existing recording.
     */
    internal fun placeRecording(source: File, relPath: String) {
        val target = File(root, relPath)
        check(!target.exists()) { "A recording already exists at $relPath" }
        target.parentFile.mkdirs()
        if (source.renameTo(target)) return
        val tmp = File(target.parentFile, target.name + ".tmp")
        source.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(tmp.renameTo(target)) { "Could not store recording" }
    }

    /** A fresh scratch directory inside the store (same file system, so files can be renamed into place). */
    internal fun newStagingDir(): File = File(root, "$STAGING/${newId()}").apply { mkdirs() }

    /**
     * Optional deletion of an attempt (e.g. a rejected one) and its recording. Never automatic.
     * The record goes first, renamed into `trash/` so it is loaded whole or not at all, then the
     * recording: an attempt never points at a missing WAV.
     */
    fun deleteAttempt(a: CaptureAttempt) {
        val record = File(sessionDir(a.sessionId), "attempts/${safe(a.id)}.json")
        if (record.exists()) {
            val tomb = File(root, "$TRASH/attempt-${a.id}-${newId()}.json")
            tomb.parentFile.mkdirs()
            if (record.renameTo(tomb)) tomb.delete() else check(record.delete()) { "Could not delete attempt ${a.id}" }
        }
        val wav = recordingFile(a)
        check(!wav.exists() || wav.delete()) { "Could not delete the recording of attempt ${a.id}" }
    }

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

    private companion object {
        const val TRASH = "trash"
        const val STAGING = "import-staging"
    }
}
