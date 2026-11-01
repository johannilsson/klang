package se.klang.app.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.klang.app.AppViewModel
import se.klang.app.Screen
import se.klang.app.TestFixtures
import se.klang.app.ui.screens.SessionScreen
import se.klang.app.ui.screens.SessionSummaryScreen
import se.klang.sessions.BuiltInProfiles
import se.klang.sessions.ProfileSource
import se.klang.sessions.ReverbTarget
import se.klang.sessions.RoomConditions
import se.klang.sessions.RoomUse
import se.klang.sessions.SessionKind

/** The plain-language summary card (#26) and the room target (#27) as the app uses them. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp")
class SummaryUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var vm: AppViewModel
    private lateinit var roomId: String
    private lateinit var sessionId: String

    @Before
    fun setUp() {
        vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        roomId = vm.createRoom("Room", "").id
        vm.addPosition(roomId, "Sofa", "", "")
        val position = vm.room(roomId)!!.positions.single()
        sessionId = vm.createSession(roomId, "Baseline", SessionKind.BASELINE, "", RoomConditions()).id
        repeat(3) {
            val a = vm.store.addAttempt(sessionId, position.id, TestFixtures.clapWav().inputStream())
            vm.store.appendAnalysis(a, TestFixtures.recordWithInvalidBand())
        }
    }

    @Test
    fun sessionScreenLeadsWithABriefCardThatOpensTheDetailsScreen() {
        compose.setContent { SessionScreen(vm, sessionId) }
        compose.onNodeWithText("IN PLAIN WORDS").assertExists()
        compose.onNodeWithText("Sofa: the speech range could not be measured. 0 of 3 claps usable.").assertExists()
        // The details (diagrams, target, hints) are not on the session screen.
        compose.onAllNodesWithText("Set a target").assertCountEquals(0)
        compose.onAllNodesWithText("What usually helps here").assertCountEquals(0)
        compose.onAllNodesWithText("0.87", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("See the details").performClick()
        assertEquals(Screen.SessionSummary(sessionId), vm.screen)
    }

    @Test
    fun theDetailsScreenHoldsTheCardsTargetAndNoInvalidValue() {
        compose.setContent { SessionSummaryScreen(vm, sessionId) }
        compose.onNodeWithText("IN PLAIN WORDS · SOFA").assertExists()
        compose.onNodeWithText("Set a target").assertExists()
        compose.onAllNodesWithText("0.87", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("significant", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun theRecordingsStayOnTheSessionScreenUnderEachPosition() {
        val second = vm.addPosition(roomId, "Window", "", "").let { vm.room(roomId)!!.positions.last() }
        vm.store.addAttempt(sessionId, second.id, ByteArray(256).inputStream(), se.klang.sessions.AttemptKind.BACKGROUND)
        compose.setContent { SessionScreen(vm, sessionId) }
        compose.onNodeWithText("Background 1").assertExists()
        compose.onAllNodesWithText("Show the numbers", substring = true).assertCountEquals(0)
    }

    @Test
    fun aSuggestedTargetIsStoredByIdAndShownWithItsSource() {
        assertTrue(vm.saveRoomTarget(roomId, RoomUse.DINING_LIVING, 45.0, BuiltInProfiles.BB93_MEETING_ROOM, null))
        val room = vm.room(roomId)!!
        assertEquals("bb93-meeting-room", room.target!!.profileId)
        assertNull("built-in profiles are stored by id and version", room.target!!.profile)
        assertEquals(45.0, room.volumeCubicMetres!!, 0.0)
        compose.setContent { SessionSummaryScreen(vm, sessionId) }
        compose.onNodeWithText("Against UK school guidance for meeting rooms (BB93, Table 6)").assertExists()
    }

    @Test
    fun yourOwnValueBecomesAUserProfileWithANewVersionWhenChanged() {
        vm.saveRoomTarget(roomId, null, null, null, 0.6)
        val v1 = vm.room(roomId)!!.target!!.profile!!
        assertTrue(v1.source is ProfileSource.User)
        assertEquals(ReverbTarget.UpperLimit(0.6), v1.target)
        vm.saveRoomTarget(roomId, null, null, null, 0.5)
        assertEquals(v1.version + 1, vm.room(roomId)!!.target!!.profileVersion)
        assertEquals("your target", vm.sessionSummary(sessionId)!!.positions.single().target!!.label)
    }

    @Test
    fun yourOwnRangeIsStoredAsATargetWithTolerance() {
        assertTrue(vm.saveRoomTarget(roomId, null, null, null, 0.6, 0.4))
        val t = vm.room(roomId)!!.target!!.profile!!.target as ReverbTarget.TargetWithTolerance
        assertEquals(0.5, t.seconds, 1e-12)
        assertEquals(0.4, t.seconds * t.lowerFactor, 1e-12)
        assertEquals(0.6, t.seconds * t.upperFactor, 1e-12)
        assertEquals("your target", vm.sessionSummary(sessionId)!!.positions.single().target!!.label)
    }

    @Test
    fun boundedReadStopsAtTheLimit() {
        val bytes = AppViewModel.readAtMost(ByteArray(100_000).inputStream(), 70_000)
        assertEquals(70_000, bytes.size)
    }
}
