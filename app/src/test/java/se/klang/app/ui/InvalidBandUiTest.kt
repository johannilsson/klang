package se.klang.app.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.klang.app.AppViewModel
import se.klang.app.TestFixtures
import se.klang.app.ui.screens.AttemptScreen
import se.klang.app.ui.screens.CapabilityScreen
import se.klang.app.ui.screens.SessionScreen
import se.klang.sessions.RoomConditions
import se.klang.sessions.SessionKind

/** Invalid bands show their reasons, never a number, even when a stored record carries a fit. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp") // tall enough that every lazy item is composed
class InvalidBandUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var vm: AppViewModel
    private lateinit var sessionId: String
    private lateinit var attemptId: String

    @Before
    fun setUp() {
        vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val room = vm.createRoom("Room", "")
        vm.addPosition(room.id, "Sofa", "", "")
        val position = vm.room(room.id)!!.positions.single()
        sessionId = vm.createSession(room.id, "Baseline", SessionKind.BASELINE, "", RoomConditions()).id
        val a = vm.store.addAttempt(sessionId, position.id, TestFixtures.clapWav().inputStream())
        attemptId = vm.store.appendAnalysis(a, TestFixtures.recordWithInvalidBand()).id
    }

    @Test
    fun helperHidesTheFitOfAnInvalidBandOnly() {
        val (invalid, valid) = TestFixtures.recordWithInvalidBand().bands
        assertNull(displayedT20(invalid))
        assertEquals(0.62, displayedT20(valid)!!.seconds, 0.0)
    }

    @Test
    fun attemptScreenShowsTheVerdictAndNoNumberForTheInvalidBand() {
        compose.setContent { AttemptScreen(vm, sessionId, attemptId) }
        compose.onNodeWithText("Not usable: the room was too noisy").assertExists()
        compose.onNodeWithContentDescription("500 Hz, not measured").assertExists()
        compose.onNodeWithContentDescription("1000 Hz, 0.62 seconds").assertExists()
        compose.onAllNodesWithText("0.87", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("0.87", substring = true).assertCountEquals(0)
        // The band's own reason is behind its row.
        compose.onNodeWithContentDescription("500 Hz, not measured").performClick()
        compose.onNodeWithText("• ${TestFixtures.INVALID_REASON}").assertExists()
        compose.onAllNodesWithText("0.87", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun sessionListShowsAVerdictAndNoValueForTheInvalidBand() {
        compose.setContent { SessionScreen(vm, sessionId) }
        compose.onNodeWithContentDescription("Clap 1, Not usable.", substring = true).assertExists()
        compose.onAllNodesWithContentDescription("0.87", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("0.87", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun capabilityTestCaptureShowsTheVerdictTheGroupedProblemAndNoStaleValue() {
        val record = TestFixtures.recordWithInvalidBand()
        vm.testCapture = AppViewModel.TestCapture(FloatArray(0), TestFixtures.RATE, TestFixtures.audioConfiguration(), emptyList(), record)
        compose.setContent { CapabilityScreen(vm) }
        compose.onNodeWithText("Not usable: the room was too noisy").assertExists()
        compose.onNodeWithText("500\u00A0Hz could not be measured.").assertExists()
        compose.onNodeWithText("T20 0.62 s · margin — dB").assertExists()
        compose.onAllNodesWithText("0.87", substring = true).assertCountEquals(0)
    }
}
