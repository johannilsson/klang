package se.klang.app.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.klang.app.AppViewModel
import se.klang.app.TestFixtures
import se.klang.app.ui.screens.AttemptScreen
import se.klang.app.ui.screens.SessionScreen
import se.klang.dsp.ReasonCode
import se.klang.sessions.AttemptVerdict
import se.klang.sessions.ReasonTexts
import se.klang.sessions.RoomConditions
import se.klang.sessions.SessionKind

/** The attempt screen and the session rows: a verdict, each problem once, the graph, no value for an invalid band. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp")
class AttemptScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var vm: AppViewModel
    private lateinit var sessionId: String
    private lateinit var goodId: String
    private lateinit var noisyId: String

    @Before
    fun setUp() {
        vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val room = vm.createRoom("Room", "")
        vm.addPosition(room.id, "Sofa", "", "")
        val position = vm.room(room.id)!!.positions.single()
        sessionId = vm.createSession(room.id, "Baseline", SessionKind.BASELINE, "", RoomConditions()).id
        val good = vm.store.addAttempt(sessionId, position.id, TestFixtures.clapWav().inputStream())
        goodId = vm.store.appendAnalysis(good, TestFixtures.goodRecord()).id
        val noisy = vm.store.addAttempt(sessionId, position.id, TestFixtures.clapWav().inputStream())
        noisyId = vm.store.appendAnalysis(noisy, TestFixtures.noisyRecord()).id
    }

    @Test
    fun aNoisyRoomIsStatedOnceWithItsAdviceOnceNotPerBand() {
        compose.setContent { AttemptScreen(vm, sessionId, noisyId) }
        compose.onNodeWithText("Not usable: the room was too noisy").assertExists()
        compose.onNodeWithText("The background was too loud.").assertExists()
        compose.onAllNodesWithText(ReasonTexts.advice(ReasonCode.INSUFFICIENT_SNR_MARGIN)!!, substring = true).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("not measured", substring = true).assertCountEquals(5)
    }

    @Test
    fun aGoodClapShowsTheResultTheGraphAndTheValues() {
        compose.setContent { AttemptScreen(vm, sessionId, goodId) }
        compose.onNodeWithText("Usable clap").assertExists()
        compose.onNodeWithText("Four of five bands could be measured.").assertExists()
        compose.onNodeWithText("250\u00A0Hz could not be measured.").assertExists()
        compose.onNodeWithContentDescription("500 Hz, 0.78 seconds").assertExists()
        compose.onNodeWithContentDescription("250 Hz, not measured").assertExists()
        compose.onNodeWithText("Your recording").assertExists()
        compose.onNodeWithText("Technical details").assertExists()
    }

    @Test
    fun theSessionRowsCarryTheSameVerdictAndTheReason() {
        compose.setContent { SessionScreen(vm, sessionId) }
        compose.onNodeWithContentDescription("Clap 1, Usable. 500 Hz 0.78 · 1k 0.74 · 2k 0.66 · 4k 0.59 s").assertExists()
        compose.onNodeWithContentDescription("Clap 2, Not usable. Room too noisy: the clap was only 23–24 dB above the noise (needs 35).").assertExists()
        compose.onNodeWithText("1 of 5 usable claps", substring = true).assertExists()
    }

    @Test
    fun theViewModelReportAgreesWithTheRows() {
        val attempts = vm.attempts(sessionId)
        assertEquals(AttemptVerdict.USABLE, vm.report(attempts[0]).verdict)
        assertEquals(AttemptVerdict.NOT_USABLE, vm.report(attempts[1]).verdict)
    }
}
