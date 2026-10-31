package se.klang.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import se.klang.app.ui.screens.AttemptScreen
import se.klang.app.ui.screens.CapabilityScreen
import se.klang.app.ui.screens.CompareScreen
import se.klang.app.ui.screens.HomeScreen
import se.klang.app.ui.screens.RoomScreen
import se.klang.app.ui.screens.SessionScreen
import se.klang.app.ui.theme.KlangTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KlangTheme {
                KlangApp(vm)
            }
        }
    }

    override fun onStop() {
        // Capture is foreground-only: leaving the app interrupts (and discards) a running attempt.
        vm.interruptCapture()
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KlangApp(vm: AppViewModel) {
    val screen = vm.screen
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    // Keep the screen on while recording so the system does not interrupt the capture.
    val view = LocalView.current
    val recording = vm.capture.let { it is CaptureUi.Recording || it is CaptureUi.Countdown }
    DisposableEffect(recording) {
        view.keepScreenOn = recording
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(titleFor(vm, screen)) },
                navigationIcon = {
                    if (vm.backStack.size > 1) TextButton(onClick = { vm.back() }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (screen) {
            Screen.Home -> HomeScreen(vm, m)
            Screen.Capability -> CapabilityScreen(vm, m)
            is Screen.RoomDetail -> RoomScreen(vm, screen.roomId, m)
            is Screen.SessionDetail -> SessionScreen(vm, screen.sessionId, m)
            is Screen.AttemptDetail -> AttemptScreen(vm, screen.sessionId, screen.attemptId, m)
            is Screen.Compare -> CompareScreen(vm, screen.roomId, m)
        }
    }
}

private fun titleFor(vm: AppViewModel, s: Screen): String = when (s) {
    Screen.Home -> "Klang"
    Screen.Capability -> "Microphone check"
    is Screen.RoomDetail -> vm.room(s.roomId)?.name ?: "Room"
    is Screen.SessionDetail -> vm.session(s.sessionId)?.name ?: "Session"
    is Screen.AttemptDetail -> "Attempt"
    is Screen.Compare -> "Compare sessions"
}
