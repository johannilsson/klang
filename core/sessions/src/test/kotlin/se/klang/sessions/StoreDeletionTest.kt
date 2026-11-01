package se.klang.sessions

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreDeletionTest {
    private fun store() = SessionStore(Files.createTempDirectory("klang-del").toFile())

    private fun populate(store: SessionStore, roomName: String = "Kitchen"): Pair<Room, List<Session>> {
        val room = store.createRoom(roomName)
        val p = store.addPosition(room.id, "Seat A")
        val sessions = listOf(SessionKind.BASELINE, SessionKind.TREATMENT).map { kind ->
            store.createSession(room.id, kind.name, kind, if (kind == SessionKind.TREATMENT) "Rug" else "").also { s ->
                repeat(2) { i -> store.addAttempt(s.id, p.id, ByteArray(64) { (it + i).toByte() }.inputStream()) }
            }
        }
        return store.room(room.id)!! to sessions
    }

    private fun filesUnder(dir: File) = dir.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `archiving only sets a flag and keeps all data`() {
        val store = store()
        val (room, sessions) = populate(store)
        store.setSessionArchived(sessions[0].id, true)
        store.setRoomArchived(room.id, true)
        val reopened = SessionStore(store.root)
        assertTrue(reopened.room(room.id)!!.archived)
        assertTrue(reopened.session(sessions[0].id)!!.archived)
        assertFalse(reopened.session(sessions[1].id)!!.archived)
        assertEquals(2, reopened.sessions(room.id).size)
        reopened.sessions(room.id).forEach { s -> reopened.attempts(s.id).forEach { assertTrue(reopened.recordingFile(it).exists()) } }
        reopened.setRoomArchived(room.id, false)
        assertFalse(reopened.room(room.id)!!.archived)
    }

    @Test
    fun `deleting a session removes its attempts and recordings and nothing else`() {
        val store = store()
        val (room, sessions) = populate(store)
        val (other, otherSessions) = populate(store, "Living room")
        val gone = sessions[0]
        val goneFiles = store.attempts(gone.id).map { store.recordingFile(it) }
        store.deleteSession(gone.id)
        assertNull(store.session(gone.id))
        assertEquals(listOf(sessions[1].id), store.sessions(room.id).map { it.id })
        goneFiles.forEach { assertFalse(it.exists()) }
        assertFalse(File(store.root, "sessions/${gone.id}").exists())
        assertEquals(2, store.attempts(sessions[1].id).size)
        assertEquals(otherSessions.map { it.id }, store.sessions(other.id).map { it.id })
        assertTrue(filesUnder(File(store.root, "trash")).isEmpty())
        store.deleteSession(gone.id) // idempotent
    }

    @Test
    fun `deleting a room removes its sessions, attempts and recordings`() {
        val store = store()
        val (room, sessions) = populate(store)
        val (other, _) = populate(store, "Living room")
        store.deleteRoom(room.id)
        assertNull(store.room(room.id))
        assertEquals(listOf(other.id), store.rooms().map { it.id })
        sessions.forEach { assertNull(store.session(it.id)); assertFalse(File(store.root, "sessions/${it.id}").exists()) }
        assertTrue(store.sessions(room.id).isEmpty())
        assertEquals(2, store.sessions(other.id).size)
        // Only the other room's four recordings remain.
        assertEquals(4, filesUnder(store.root).count { it.extension == "wav" })
    }

    @Test
    fun `an interrupted delete leaves a loadable store and is finished on reopen`() {
        val store = store()
        val (room, sessions) = populate(store)
        // Simulate a crash after the tombstone rename but before its contents were removed.
        val tomb = File(store.root, "trash/session-${sessions[0].id}-crash")
        tomb.parentFile.mkdirs()
        assertTrue(File(store.root, "sessions/${sessions[0].id}").renameTo(tomb))
        assertEquals(listOf(sessions[1].id), store.sessions(room.id).map { it.id })
        store.sessions(room.id).forEach { s -> store.attempts(s.id).forEach { assertTrue(store.recordingFile(it).exists()) } }

        val reopened = SessionStore(store.root)
        assertFalse(tomb.exists())
        assertNotNull(reopened.room(room.id))
        assertEquals(listOf(sessions[1].id), reopened.sessions(room.id).map { it.id })
    }
}
