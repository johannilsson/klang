package se.klang.app.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
    fun attemptScreenShowsReasonInsteadOfTheInvalidBandsNumber() {
        compose.setContent { AttemptScreen(vm, sessionId, attemptId) }
        compose.onNodeWithText("T20 0.62 s").assertExists()
        compose.onNodeWithText("• ${TestFixtures.INVALID_REASON}").assertExists()
        compose.onAllNodesWithText("0.87", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("T20 — s").assertCountEquals(1)
    }

    @Test
    fun sessionListShowsNoValueForTheInvalidBand() {
        compose.setContent { SessionScreen(vm, sessionId) }
        compose.onNodeWithContentDescription("500 Hz, Invalid").assertExists()
        compose.onNodeWithContentDescription("1000 Hz, Valid, 0.62 seconds").assertExists()
        compose.onAllNodesWithContentDescription("0.87", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("0.87", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }
}
