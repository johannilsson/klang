package se.klang.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.klang.app.ui.positionDeleteWhat
import se.klang.app.ui.screens.positionEditWarning
import se.klang.sessions.AttemptKind
import se.klang.sessions.RoomConditions
import se.klang.sessions.SessionKind
import java.io.File

/** Edits and deletes from #25 through the view model. The store operations themselves are tested in core. */
@RunWith(RobolectricTestRunner::class)
class EditViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private class Fixture(val vm: AppViewModel, val roomId: String, val positionId: String, val otherPositionId: String, val sessionIds: List<String>)

    /** Position P1 has 2 claps + 1 background in the first session and 1 clap in the second; P2 has one clap. */
    private fun fixture(): Fixture {
        File(app.filesDir, "klang").deleteRecursively()
        val vm = AppViewModel(app)
        val room = vm.createRoom("Room", "")
        vm.addPosition(room.id, "P1", "", "")
        vm.addPosition(room.id, "P2", "", "")
        val (p1, p2) = vm.room(room.id)!!.positions
        val sessions = listOf(SessionKind.BASELINE, SessionKind.TREATMENT).map { vm.createSession(room.id, it.name, it, "", RoomConditions()) }
        val wav = { TestFixtures.clapWav().inputStream() }
        vm.store.addAttempt(sessions[0].id, p1.id, wav())
        vm.store.addAttempt(sessions[0].id, p1.id, wav())
        vm.store.addAttempt(sessions[0].id, p1.id, wav(), AttemptKind.BACKGROUND)
        vm.store.addAttempt(sessions[1].id, p1.id, wav())
        vm.store.addAttempt(sessions[1].id, p2.id, wav())
        return Fixture(vm, room.id, p1.id, p2.id, sessions.map { it.id })
    }

    @Test
    fun positionDeleteConfirmationCountsRecordingsAndSessions() {
        val f = fixture()
        val impact = f.vm.positionDeleteImpact(f.roomId, f.positionId)
        assertEquals(AppViewModel.PositionDeleteImpact(recordings = 4, sessions = 2), impact)
        assertEquals("Position “P1” with its 4 recordings in 2 sessions", positionDeleteWhat("P1", impact.recordings, impact.sessions))
        assertEquals(AppViewModel.PositionDeleteImpact(1, 1), f.vm.positionDeleteImpact(f.roomId, f.otherPositionId))
        assertEquals("Position “P2” with its 1 recording in 1 session", positionDeleteWhat("P2", 1, 1))
        assertEquals("Position “P3” (it has no recordings)", positionDeleteWhat("P3", 0, 0))
        assertTrue(positionEditWarning(4, 2).contains("older sessions will show the new description"))
    }

    @Test
    fun deletingAPositionRemovesItsRecordingsAndClosesTheirScreens() {
        val f = fixture()
        val gone = f.vm.attempts(f.sessionIds[0]).first { it.positionId == f.positionId }
        f.vm.navigate(Screen.RoomDetail(f.roomId))
        f.vm.navigate(Screen.SessionDetail(f.sessionIds[0]))
        f.vm.navigate(Screen.AttemptDetail(f.sessionIds[0], gone.id))
        assertTrue(f.vm.deletePosition(f.roomId, f.positionId))
        awaitIdle { f.vm.screen !is Screen.AttemptDetail }
        assertEquals(Screen.SessionDetail(f.sessionIds[0]), f.vm.screen)
        assertEquals(listOf(f.otherPositionId), f.vm.room(f.roomId)!!.positions.map { it.id })
        assertTrue(f.sessionIds.flatMap { f.vm.attempts(it) }.all { it.positionId == f.otherPositionId })
        assertFalse(f.vm.store.recordingFile(gone).exists())
    }

    @Test
    fun editsAreRefusedDuringACaptureAndAllowedAfterIt() {
        val f = fixture()
        // The countdown runs before the microphone opens, so a capture can start without audio hardware.
        f.vm.record(f.sessionIds[0], f.positionId, AttemptKind.IMPULSE)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(f.vm.isCapturing)
        assertBlocked(f)
        f.vm.cancelCapture()
        assertFalse(f.vm.isCapturing)
        assertTrue(f.vm.updateRoom(f.roomId, "Renamed", "Notes"))
        assertEquals("Renamed", f.vm.room(f.roomId)!!.name)
        assertNotNull(f.vm.room(f.roomId)!!.updatedAtEpochMs)
    }

    @Test
    fun editsAreRefusedDuringReanalysis() {
        val f = fixture()
        f.vm.reanalyze(f.sessionIds)
        assertTrue(f.vm.reanalyzing)
        assertBlocked(f)
        awaitIdle { !f.vm.reanalyzing }
        assertTrue(f.vm.updateSession(f.sessionIds[1], "Repeat", SessionKind.REPEAT_BASELINE, "", RoomConditions(notes = "x")))
        assertEquals(SessionKind.REPEAT_BASELINE, f.vm.session(f.sessionIds[1])!!.kind)
        assertTrue(f.vm.movePosition(f.roomId, f.otherPositionId, -1))
        assertEquals(listOf(f.otherPositionId, f.positionId), f.vm.room(f.roomId)!!.positions.map { it.id })
    }

    private fun assertBlocked(f: Fixture) {
        val vm = f.vm
        val room = vm.room(f.roomId)!!
        val sessions = f.sessionIds.map { vm.session(it)!! }
        val attempts = f.sessionIds.flatMap { vm.attempts(it) }
        assertTrue(vm.editingBlocked)
        assertFalse(vm.updateRoom(f.roomId, "Changed", "Changed"))
        assertFalse(vm.updatePosition(f.roomId, f.positionId, "Changed", "", ""))
        assertFalse(vm.movePosition(f.roomId, f.otherPositionId, -1))
        assertFalse(vm.updateSession(f.sessionIds[0], "Changed", SessionKind.TREATMENT, "x", RoomConditions()))
        assertFalse(vm.deletePosition(f.roomId, f.positionId))
        assertFalse(vm.deleteAttempt(attempts.first()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(room, vm.room(f.roomId))
        assertEquals(sessions, f.sessionIds.map { vm.session(it) })
        assertEquals(attempts.map { it.id }.toSet(), f.sessionIds.flatMap { vm.attempts(it) }.map { it.id }.toSet())
    }

    private fun awaitIdle(timeoutMs: Long = 60_000, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
