package se.klang.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalView
import se.klang.app.ui.screens.AttemptScreen
import se.klang.app.ui.screens.CapabilityScreen
import se.klang.app.ui.screens.CompareScreen
import se.klang.app.ui.screens.HomeScreen
import se.klang.app.ui.screens.RoomScreen
import se.klang.app.ui.screens.SessionScreen
import se.klang.app.ui.screens.SheetHost
import se.klang.app.ui.screens.TopBarMenu
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

    // Home gets a large title that collapses on scroll; other screens a small bar that tints when content scrolls under it.
    // A fresh behaviour per screen so a collapsed state does not leak into the next screen.
    val home = screen == Screen.Home
    val scrollBehavior = key(screen) {
        if (home) TopAppBarDefaults.exitUntilCollapsedScrollBehavior() else TopAppBarDefaults.pinnedScrollBehavior()
    }
    val navigationIcon: @Composable () -> Unit = {
        if (vm.backStack.size > 1) {
            IconButton(onClick = { vm.back() }, enabled = !vm.isCapturing) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate up")
            }
        }
    }
    val title: @Composable () -> Unit = { Text(titleFor(vm, screen), maxLines = 1, overflow = TextOverflow.Ellipsis) }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (home) {
                LargeTopAppBar(title = title, navigationIcon = navigationIcon, actions = { TopBarMenu(vm, screen) }, scrollBehavior = scrollBehavior)
            } else {
                TopAppBar(title = title, navigationIcon = navigationIcon, actions = { TopBarMenu(vm, screen) }, scrollBehavior = scrollBehavior)
            }
        },
        floatingActionButton = { MainAction(vm, screen) },
    ) { padding ->
        // Forward navigation slides in from the end, back slides out: the platform's default feel.
        val depth = vm.backStack.size
        AnimatedContent(
            targetState = screen to depth,
            transitionSpec = {
                val forward = targetState.second >= initialState.second
                val dir = if (forward) 1 else -1
                (slideInHorizontally { w -> dir * w / 5 } + fadeIn()) togetherWith (slideOutHorizontally { w -> -dir * w / 5 } + fadeOut())
            },
            contentKey = { it.first },
            label = "screen",
        ) { (target, _) ->
            val m = Modifier.padding(padding)
            when (target) {
                Screen.Home -> HomeScreen(vm, m)
                Screen.Capability -> CapabilityScreen(vm, m)
                is Screen.RoomDetail -> RoomScreen(vm, target.roomId, m)
                is Screen.SessionDetail -> SessionScreen(vm, target.sessionId, m)
                is Screen.AttemptDetail -> AttemptScreen(vm, target.sessionId, target.attemptId, m)
                is Screen.Compare -> CompareScreen(vm, target.roomId, m)
            }
        }
    }
    SheetHost(vm)
}

/** The one primary action per setup screen. Rooms need a position before a session makes sense. */
@Composable
private fun MainAction(vm: AppViewModel, screen: Screen) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val (label, sheet) = when (screen) {
        Screen.Home -> "New room" to Sheet.NewRoom
        is Screen.RoomDetail -> {
            val room = vm.room(screen.roomId) ?: return
            if (room.positions.isEmpty()) "Add position" to Sheet.AddPosition(room.id) else "New session" to Sheet.NewSession(room.id)
        }
        else -> return
    }
    ExtendedFloatingActionButton(
        onClick = { vm.sheet = sheet },
        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
        text = { Text(label) },
    )
}

private fun titleFor(vm: AppViewModel, s: Screen): String = when (s) {
    Screen.Home -> "Klang"
    Screen.Capability -> "Microphone check"
    is Screen.RoomDetail -> vm.room(s.roomId)?.name ?: "Room"
    is Screen.SessionDetail -> vm.session(s.sessionId)?.name ?: "Session"
    is Screen.AttemptDetail -> "Attempt"
    is Screen.Compare -> "Compare sessions"
}
