package se.klang.sessions

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Metadata edits (#25): room, positions, sessions. Recordings and analyses are never edited. */
class StoreEditTest {
    private var now = 1_000L
    private fun store(root: File = Files.createTempDirectory("klang-edit").toFile()) = SessionStore(root) { now++ }

    /** A room with positions A, B, C; two sessions; A has two claps and a background in each session, B one clap in the first. */
    private class Fixture(val store: SessionStore, val room: Room, val sessions: List<Session>) {
        val a get() = room.positions[0]
        val b get() = room.positions[1]
        val c get() = room.positions[2]
    }

    private fun fixture(store: SessionStore = store()): Fixture {
        val room = store.createRoom("Kitchen", "Tiled floor")
        val positions = listOf("A", "B", "C").map { store.addPosition(room.id, it, "Ear height", "1.5 m in front") }
        val sessions = listOf(SessionKind.BASELINE, SessionKind.TREATMENT).mapIndexed { si, kind ->
            store.createSession(room.id, kind.name, kind, if (kind == SessionKind.TREATMENT) "Rug" else "").also { s ->
                repeat(2) { i -> store.addAttempt(s.id, positions[0].id, ByteArray(64) { (it + i + si).toByte() }.inputStream()) }
                store.addAttempt(s.id, positions[0].id, ByteArray(32) { 1 }.inputStream(), AttemptKind.BACKGROUND)
                if (si == 0) store.addAttempt(s.id, positions[1].id, ByteArray(64) { 9 }.inputStream())
            }
        }
        return Fixture(store, store.room(room.id)!!, sessions)
    }

    private fun allAttempts(store: SessionStore, roomId: String) = store.sessions(roomId).flatMap { store.attempts(it.id) }
    private fun wavCount(store: SessionStore) = store.root.walkTopDown().count { it.isFile && it.extension == "wav" }

    @Test
    fun `a room is renamed and its notes changed, with the edit time`() {
        val f = fixture()
        assertNull(f.room.updatedAtEpochMs)
        val edited = f.store.updateRoom(f.room.id, "Kitchen and dining", "Tiled floor, new curtains")
        val reloaded = SessionStore(f.store.root).room(f.room.id)!!
        assertEquals(edited, reloaded)
        assertEquals("Kitchen and dining", reloaded.name)
        assertEquals("Tiled floor, new curtains", reloaded.notes)
        assertTrue(reloaded.updatedAtEpochMs!! > reloaded.createdAtEpochMs)
        assertEquals(f.room.positions, reloaded.positions)
        assertFailsWith<IllegalArgumentException> { f.store.updateRoom(f.room.id, "  ", "") }
    }

    @Test
    fun `a position is edited in place and its recordings are untouched`() {
        val f = fixture()
        val before = allAttempts(f.store, f.room.id)
        val p = f.store.updatePosition(f.room.id, f.a.id, "Seat by window", "On a stand, 1.2 m", "2 m toward the kitchen")
        val room = f.store.room(f.room.id)!!
        assertEquals(listOf(p, f.b, f.c), room.positions)
        assertEquals(f.a.id, p.id)
        assertEquals("2 m toward the kitchen", p.clapSpot)
        assertTrue(p.updatedAtEpochMs != null)
        assertNull(room.positions[1].updatedAtEpochMs)
        assertEquals(before, allAttempts(f.store, f.room.id))
        assertFailsWith<IllegalArgumentException> { f.store.updatePosition(f.room.id, "missing", "X", "", "") }
    }

    @Test
    fun `positions are reordered by moving one up or down, clamped to the list`() {
        val f = fixture()
        val ids = { f.store.room(f.room.id)!!.positions.map { it.name } }
        f.store.movePosition(f.room.id, f.c.id, -1)
        assertEquals(listOf("A", "C", "B"), ids())
        f.store.movePosition(f.room.id, f.a.id, 1)
        assertEquals(listOf("C", "A", "B"), ids())
        val unchanged = f.store.room(f.room.id)!!
        assertEquals(unchanged, f.store.movePosition(f.room.id, f.c.id, -1)) // already first
        f.store.movePosition(f.room.id, f.c.id, 10)
        assertEquals(listOf("A", "B", "C"), ids())
        assertTrue(f.store.room(f.room.id)!!.updatedAtEpochMs != null)
    }

    @Test
    fun `a session's name, kind, intervention and conditions are edited and its recordings kept`() {
        val f = fixture()
        val s = f.sessions[1]
        val attempts = f.store.attempts(s.id)
        val conditions = RoomConditions("Door open", "New sofa", "2", appliancesOff = false, notes = "Rain")
        val edited = f.store.updateSession(s.id, "Repeat 1", SessionKind.REPEAT_BASELINE, "", conditions)
        val reloaded = SessionStore(f.store.root).session(s.id)!!
        assertEquals(edited, reloaded)
        assertEquals(SessionKind.REPEAT_BASELINE, reloaded.kind)
        assertEquals("", reloaded.intervention)
        assertEquals(conditions, reloaded.conditions)
        assertEquals(s.createdAtEpochMs, reloaded.createdAtEpochMs)
        assertTrue(reloaded.updatedAtEpochMs!! > s.createdAtEpochMs)
        assertEquals(attempts, f.store.attempts(s.id))
        assertFailsWith<IllegalArgumentException> { f.store.updateSession(s.id, "", SessionKind.BASELINE, "", conditions) }
    }

    @Test
    fun `records written before the edit time existed still load`() {
        val f = fixture()
        val file = File(f.store.root, "rooms/${f.room.id}.json")
        val text = file.readText()
        assertTrue("\"updatedAtEpochMs\": null" in text)
        file.writeText(text.replace(Regex(",\\s*\"updatedAtEpochMs\": null"), ""))
        assertEquals(f.room, f.store.room(f.room.id))
    }

    @Test
    fun `deleting a position without recordings only removes it from the room`() {
        val f = fixture()
        val before = allAttempts(f.store, f.room.id)
        assertTrue(f.store.positionAttempts(f.room.id, f.c.id).isEmpty())
        val room = f.store.deletePosition(f.room.id, f.c.id)
        assertEquals(listOf(f.a.id, f.b.id), room.positions.map { it.id })
        assertEquals(room, f.store.room(f.room.id))
        assertEquals(before, allAttempts(f.store, f.room.id))
    }

    @Test
    fun `deleting a position removes its claps and backgrounds in every session and nothing else`() {
        val f = fixture()
        val counts = f.store.positionAttempts(f.room.id, f.a.id)
        assertEquals(f.sessions.map { it.id }, counts.keys.map { it.id })
        assertEquals(listOf(3, 3), counts.values.map { it.size })
        val goneFiles = counts.values.flatten().map { f.store.recordingFile(it) }
        val wavsBefore = wavCount(f.store)

        f.store.deletePosition(f.room.id, f.a.id)

        val reopened = SessionStore(f.store.root)
        assertEquals(listOf(f.b.id, f.c.id), reopened.room(f.room.id)!!.positions.map { it.id })
        val left = allAttempts(reopened, f.room.id)
        assertTrue(left.none { it.positionId == f.a.id })
        assertEquals(1, left.count { it.positionId == f.b.id })
        left.forEach { assertTrue(reopened.recordingFile(it).exists()) }
        goneFiles.forEach { assertFalse(it.exists()) }
        assertEquals(wavsBefore - 6, wavCount(reopened))
        assertEquals(2, reopened.sessions(f.room.id).size)
        assertTrue(File(reopened.root, "trash").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an interrupted position delete leaves a loadable store and can be finished`() {
        val f = fixture()
        val targets = f.store.positionAttempts(f.room.id, f.a.id).values.flatten()
        // Crash after the first attempt record went to trash, before its WAV and the other attempts were removed.
        val first = targets.first()
        val tomb = File(f.store.root, "trash/attempt-${first.id}-crash.json")
        tomb.parentFile.mkdirs()
        assertTrue(File(f.store.root, "sessions/${first.sessionId}/attempts/${first.id}.json").renameTo(tomb))

        val reopened = SessionStore(f.store.root)
        assertFalse(tomb.exists())
        assertTrue(reopened.room(f.room.id)!!.positions.any { it.id == f.a.id })
        val left = allAttempts(reopened, f.room.id)
        assertEquals(targets.size - 1, left.count { it.positionId == f.a.id })
        left.forEach { assertTrue(reopened.recordingFile(it).exists(), "no attempt points at a missing WAV") }

        reopened.deletePosition(f.room.id, f.a.id)
        assertTrue(allAttempts(reopened, f.room.id).none { it.positionId == f.a.id })
        assertTrue(reopened.room(f.room.id)!!.positions.none { it.id == f.a.id })
    }

    @Test
    fun `deleting an attempt goes through trash and removes record and recording`() {
        val f = fixture()
        val a = f.store.attempts(f.sessions[0].id).first()
        f.store.deleteAttempt(a)
        assertTrue(f.store.attempts(f.sessions[0].id).none { it.id == a.id })
        assertFalse(f.store.recordingFile(a).exists())
        assertTrue(File(f.store.root, "trash").listFiles().orEmpty().isEmpty())
        f.store.deleteAttempt(a) // idempotent
    }

    private fun export(store: SessionStore, roomId: String): ByteArray {
        val exporter = Exporter(store, "test")
        return ByteArrayOutputStream().also { exporter.writeZip(exporter.document(roomId, now = 0), it) }.toByteArray()
    }

    @Test
    fun `import never overwrites locally edited metadata`() {
        val f = fixture()
        val zip = export(f.store, f.room.id)
        val local = store()
        Importer(local).import(zip.inputStream())
        val s = f.sessions[0]
        local.updateRoom(f.room.id, "Renamed here", "Edited notes")
        local.updatePosition(f.room.id, f.a.id, "A edited", "Shelf", "3 m")
        local.movePosition(f.room.id, f.c.id, -2)
        local.updateSession(s.id, "Baseline (edited)", SessionKind.REPEAT_BASELINE, "", RoomConditions(notes = "edited"))
        val roomBefore = local.room(f.room.id)!!
        val sessionBefore = local.session(s.id)!!

        val result = Importer(local).import(zip.inputStream())
        assertEquals(0, result.positionsAdded)
        assertEquals(roomBefore, local.room(f.room.id))
        assertEquals(sessionBefore, local.session(s.id))
    }

    @Test
    fun `importing an older export brings back a position deleted locally, with its recordings`() {
        // Documented consequence of "import adds what is missing": a delete is not remembered.
        val f = fixture()
        val zip = export(f.store, f.room.id)
        f.store.deletePosition(f.room.id, f.a.id)
        val result = Importer(f.store).import(zip.inputStream())
        assertEquals(1, result.positionsAdded)
        assertEquals(6, result.attemptsAdded)
        assertTrue(f.store.room(f.room.id)!!.positions.any { it.id == f.a.id })
    }
}
