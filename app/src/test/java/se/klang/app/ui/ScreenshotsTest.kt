package se.klang.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.klang.app.AppViewModel
import se.klang.app.ui.screens.AttemptScreen
import se.klang.app.ui.screens.CompareScreen
import se.klang.app.ui.screens.RoomScreen
import se.klang.app.ui.screens.SessionScreen
import se.klang.app.ui.screens.SessionSummaryScreen
import se.klang.app.ui.theme.KlangTheme
import se.klang.dsp.Wav
import se.klang.dsp.WavEncoding
import se.klang.sessions.AnalysisService
import se.klang.sessions.AttemptKind
import se.klang.sessions.BuiltInProfiles
import se.klang.sessions.RoomConditions
import se.klang.sessions.RoomUse
import se.klang.sessions.SessionKind
import java.io.File
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln

/**
 * Renders the main screens to PNG files, from the real analyser run on synthetic recordings, so a UI change can be
 * looked at without a phone. Not part of the normal test run: `scripts/screens.sh` sets KLANG_SCREENSHOTS to the
 * output folder and runs only this class. See `.claude/skills/render-screens`.
 *
 * What it cannot show: dialogs and sheets (the target sheet, the recording sheet, menus) live in their own window
 * and are not part of the captured root. The recordings are synthetic; the screens show what the analyser really
 * makes of them, not a real room.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h3000dp-xxhdpi")
class ScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    /** Five seconds: noise at [bg] (RMS), a decaying clap of amplitude [amp] and reverberation time [t60] at 1.5 s. */
    private fun clap(bg: Double, amp: Double, t60: Double, seed: Long, tapAt: Double? = null, laterAt: Double? = null): FloatArray {
        val rate = 48_000
        val rnd = Random(seed)
        val k = 3.0 * ln(10.0) / t60
        return FloatArray(5 * rate) { i ->
            val t = (i - 1.5 * rate) / rate
            var v = bg * rnd.nextGaussian()
            if (t >= 0) v += amp * exp(-k * t) * rnd.nextGaussian()
            val ts = i.toDouble() / rate
            if (tapAt != null && ts in tapAt..(tapAt + 0.03)) v += 0.04 * rnd.nextGaussian()
            if (laterAt != null && ts in laterAt..(laterAt + 0.04)) v += 0.004 * rnd.nextGaussian()
            v.toFloat()
        }
    }

    private fun wav(x: FloatArray) = Wav.toBytes(x, 48_000, WavEncoding.FLOAT_32).inputStream()

    @Test
    fun render() {
        val dir = File(System.getenv("KLANG_SCREENSHOTS")?.takeIf { it.isNotEmpty() } ?: return).apply { mkdirs() }
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        val room = vm.createRoom("Kitchen", "Tiled floor, big window")
        vm.addPosition(room.id, "Seat A", "Table, ear height", "1.8 m toward the window")
        vm.addPosition(room.id, "Seat B", "Table, ear height", "1.8 m toward the door")
        val seatA = vm.room(room.id)!!.positions[0]
        val seatB = vm.room(room.id)!!.positions[1]
        vm.saveRoomTarget(room.id, RoomUse.DINING_LIVING, 45.0, BuiltInProfiles.BB93_SMALL_GROUP_ROOM, null)
        val service = AnalysisService(vm.store)

        // One session with every kind of recording the app has to explain.
        val baseline = vm.createSession(room.id, "Baseline", SessionKind.BASELINE, "", RoomConditions())
        val attempts = listOf(
            "clean" to clap(2e-4, 0.07, 0.7, 11),
            "noisy" to clap(0.006, 0.07, 0.6, 12),
            "tap" to clap(2e-4, 0.07, 0.7, 13, tapAt = 0.6, laterAt = 3.2),
            "loud" to clap(2e-4, 0.24, 0.7, 14),
        ).map { (name, x) -> name to vm.store.addAttempt(baseline.id, seatA.id, wav(x)).also(service::analyze) }
        vm.store.addAttempt(baseline.id, seatA.id, wav(clap(2e-4, 0.0, 0.7, 5)), AttemptKind.BACKGROUND)
        // Seat B and three more clean claps, so the summary has a value.
        repeat(4) { vm.store.addAttempt(baseline.id, seatB.id, wav(clap(2e-4, 0.07, 0.8, 20L + it))).also(service::analyze) }
        repeat(3) { vm.store.addAttempt(baseline.id, seatA.id, wav(clap(2e-4, 0.07, 0.7, 30L + it))).also(service::analyze) }

        // A before/after comparison: baseline, repeat baseline and a treatment with a shorter decay.
        val repeat = vm.createSession(room.id, "Repeat baseline", SessionKind.REPEAT_BASELINE, "", RoomConditions())
        val treatment = vm.createSession(room.id, "Curtains", SessionKind.TREATMENT, "Heavy curtains over the window", RoomConditions())
        for (p in listOf(seatA, seatB)) {
            repeat(4) { vm.store.addAttempt(repeat.id, p.id, wav(clap(2e-4, 0.07, 0.72, 40L + it))).also(service::analyze) }
            repeat(4) { vm.store.addAttempt(treatment.id, p.id, wav(clap(2e-4, 0.07, 0.5, 50L + it))).also(service::analyze) }
        }

        val scenes: List<Pair<String, @Composable () -> Unit>> = buildList {
            add("session" to { SessionScreen(vm, baseline.id) })
            attempts.forEach { (name, a) -> add("attempt-$name" to { AttemptScreen(vm, baseline.id, a.id) }) }
            add("session-details" to { SessionSummaryScreen(vm, baseline.id) })
            add("room" to { RoomScreen(vm, room.id) })
            add("compare" to { CompareScreen(vm, room.id) })
        }
        var index by mutableIntStateOf(0)
        compose.setContent { KlangTheme { scenes[index].second() } }
        scenes.forEachIndexed { i, (name, _) ->
            index = i
            compose.waitForIdle()
            Thread.sleep(600) // the recording graphs are read from the WAV files off the main thread
            compose.waitForIdle()
            val bitmap = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
            File(dir, "$name.png").outputStream().use { cropBlank(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** The window is taller than any screen; cut the empty background off the bottom. */
    private fun cropBlank(b: Bitmap): Bitmap {
        val background = b.getPixel(2, b.height - 3)
        var last = b.height - 1
        while (last > 0 && (0 until b.width step 8).all { b.getPixel(it, last) == background }) last--
        return Bitmap.createBitmap(b, 0, 0, b.width, minOf(b.height, last + 24))
    }
}
