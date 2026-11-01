package se.klang.app.ui

import android.app.Application
import androidx.compose.material3.SheetValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.klang.app.AppViewModel
import se.klang.app.CaptureUi
import se.klang.app.ui.screens.CapturePanel
import se.klang.app.ui.screens.SheetHost
import se.klang.app.ui.screens.captureSheetMayChange
import se.klang.app.ui.screens.showsSheet
import se.klang.sessions.AttemptKind

/** A running capture shows in a sheet that cannot be swiped, tapped or backed away. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1400dp")
class CaptureSheetTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var vm: AppViewModel

    @Before
    fun setUp() {
        vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
    }

    @Test
    fun theSheetNeverSettlesHidden() {
        assertFalse(captureSheetMayChange(SheetValue.Hidden))
        assertTrue(captureSheetMayChange(SheetValue.Expanded))
        assertTrue(captureSheetMayChange(SheetValue.PartiallyExpanded))
    }

    @Test
    fun onlyTheLiveStatesShowInTheSheet() {
        val rec = CaptureUi.Recording("CLAP", 2.0, 5.0, AttemptKind.IMPULSE)
        assertTrue(CaptureUi.Countdown(3, AttemptKind.IMPULSE).showsSheet())
        assertTrue(rec.showsSheet())
        assertTrue(CaptureUi.Analyzing.showsSheet())
        assertTrue(CaptureUi.Practice(30.0, 1.0, -30.0, -10.0, 1).showsSheet())
        assertTrue("a finished practice stays until Done", CaptureUi.Practice(30.0, 30.0, -30.0, -10.0, 1, finished = true).showsSheet())
        assertFalse(CaptureUi.Idle.showsSheet())
        assertFalse("the saved attempt shows inline", CaptureUi.Done("a").showsSheet())
        assertFalse("an error shows inline", CaptureUi.Problem("no permission").showsSheet())
    }

    @Test
    fun theCountdownAndTheClapPromptAreInTheSheetNotInline() {
        vm.capture = CaptureUi.Countdown(3, AttemptKind.IMPULSE)
        compose.setContent { SheetHost(vm); CapturePanel(vm) }
        compose.onNodeWithText("Get ready… 3").assertExists()
        compose.onAllNodesWithText("Get ready… 3").assertCountEquals(1)
        compose.onNodeWithText("Cancel").assertExists()

        vm.capture = CaptureUi.Recording("CLAP", 2.0, 5.0, AttemptKind.IMPULSE)
        compose.waitForIdle()
        compose.onNodeWithText("Clap once now").assertExists()
        compose.onAllNodesWithText("Clap once now").assertCountEquals(1)
    }

    @Test
    fun cancellingFromTheSheetEndsItAndTheOutcomeStaysInline() {
        vm.capture = CaptureUi.Countdown(2, AttemptKind.IMPULSE)
        compose.setContent { SheetHost(vm); CapturePanel(vm) }
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(CaptureUi.Idle, vm.capture)
        compose.onAllNodesWithText("Get ready… 2").assertCountEquals(0)

        vm.capture = CaptureUi.Problem("Microphone permission is needed")
        compose.waitForIdle()
        compose.onNodeWithText("Microphone permission is needed").assertExists()
    }

    @Test
    fun aFinishedPracticeCanBeClosedWithDone() {
        vm.capture = CaptureUi.Practice(30.0, 30.0, -30.0, -10.0, 2, finished = true)
        compose.setContent { SheetHost(vm) }
        compose.onNodeWithText("Practice finished").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertEquals(CaptureUi.Idle, vm.capture)
    }
}
