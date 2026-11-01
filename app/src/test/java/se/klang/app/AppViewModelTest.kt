package se.klang.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.klang.dsp.AnalysisConfiguration
import se.klang.sessions.AttemptKind
import se.klang.sessions.CaptureEvent
import se.klang.sessions.SessionKind
import java.io.File

/** App wiring of the store and of re-analysis. The store itself is tested in core. */
@RunWith(RobolectricTestRunner::class)
class AppViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun storeLivesInAppPrivateFilesAndSurvivesAProcessRestart() {
        val first = AppViewModel(app)
        assertEquals(File(app.filesDir, "klang"), first.store.root)
        val room = first.createRoom("Living room", "")
        first.addPosition(room.id, "Sofa", "", "")
        val session = first.createSession(room.id, "Baseline", SessionKind.BASELINE, "", se.klang.sessions.RoomConditions())
        val position = first.room(room.id)!!.positions.single()
        val events = listOf(CaptureEvent("SHORT_READ", 4800, "100 of 960 frames"))
        val attempt = first.store.addAttempt(session.id, position.id, TestFixtures.clapWav().inputStream(), AttemptKind.IMPULSE, captureEvents = events)
        first.store.appendAnalysis(attempt, TestFixtures.recordWithInvalidBand())

        // A new process builds a new view model and store on the same directory.
        val second = AppViewModel(app)
        assertEquals(listOf("Living room"), second.rooms().map { it.name })
        assertEquals(listOf("Sofa"), second.room(room.id)!!.positions.map { it.name })
        assertEquals(listOf("Baseline"), second.sessions(room.id).map { it.name })
        val reloaded = second.attempts(session.id).single()
        assertEquals("capture events are kept with the attempt", events, reloaded.captureEvents)
        assertEquals(TestFixtures.recordWithInvalidBand().bands, reloaded.latestAnalysis()!!.bands)
        assertTrue(second.store.recordingFile(reloaded).isFile)
    }

    @Test
    fun reanalysisContinuesPastAFailingSessionAndReportsIt() {
        val vm = AppViewModel(app)
        val room = vm.createRoom("Room", "")
        vm.addPosition(room.id, "P1", "", "")
        val position = vm.room(room.id)!!.positions.single()
        val ids = listOf("Baseline", "Broken", "Treatment").map { name ->
            val s = vm.createSession(room.id, name, SessionKind.TREATMENT, "", se.klang.sessions.RoomConditions())
            vm.store.addAttempt(s.id, position.id, TestFixtures.clapWav().inputStream())
            s.id
        }
        val broken = vm.attempts(ids[1]).single()
        vm.store.recordingFile(broken).writeText("not a WAV file")
        val revision = vm.revision

        vm.reanalyze(ids)
        assertTrue(vm.reanalyzing)
        awaitIdle { !vm.reanalyzing }

        val version = AnalysisConfiguration.VERSION
        assertNotNull("first session analysed", vm.attempts(ids[0]).single().analysis(version))
        assertNull("failing session has no result", vm.attempts(ids[1]).single().analysis(version))
        assertNotNull("session after the failure still analysed", vm.attempts(ids[2]).single().analysis(version))
        val error = vm.reanalysisError
        assertNotNull(error)
        assertTrue(error!!, error.startsWith("Re-analysis failed for:\nBroken: "))
        assertFalse(error, error.contains("Baseline") || error.contains("Treatment"))
        assertTrue("screens are told to re-read", vm.revision > revision)
        assertTrue("the recording is never replaced", vm.store.recordingFile(broken).readText() == "not a WAV file")
    }

    @Test
    fun successfulReanalysisClearsAnEarlierError() {
        val vm = AppViewModel(app)
        val room = vm.createRoom("Room", "")
        vm.addPosition(room.id, "P1", "", "")
        val position = vm.room(room.id)!!.positions.single()
        val s = vm.createSession(room.id, "S", SessionKind.BASELINE, "", se.klang.sessions.RoomConditions())
        val a = vm.store.addAttempt(s.id, position.id, TestFixtures.clapWav().inputStream())
        val wav = vm.store.recordingFile(a).readBytes()
        vm.store.recordingFile(a).writeText("broken")
        vm.reanalyze(s.id)
        awaitIdle { !vm.reanalyzing }
        assertNotNull(vm.reanalysisError)

        vm.store.recordingFile(a).writeBytes(wav)
        vm.reanalyze(s.id)
        awaitIdle { !vm.reanalyzing }
        assertNull(vm.reanalysisError)
        assertNotNull(vm.attempts(s.id).single().analysis(AnalysisConfiguration.VERSION))
    }

    /** Runs the main looper (where results are posted) until [done] or a timeout. */
    private fun awaitIdle(timeoutMs: Long = 60_000, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
