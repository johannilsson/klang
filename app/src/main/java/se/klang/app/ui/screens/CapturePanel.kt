package se.klang.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.CaptureUi
import se.klang.app.ui.fmt
import se.klang.sessions.AttemptKind
import kotlin.math.ceil

/**
 * The recording itself runs in [CaptureSheet], so it cannot be missed when the screen is scrolled. What stays
 * inline is the outcome: the saved attempt, or why nothing was recorded.
 */
@Composable
fun CapturePanel(vm: AppViewModel, onDoneContent: @Composable (attemptId: String) -> Unit = {}) {
    when (val c = vm.capture) {
        CaptureUi.Idle, CaptureUi.Analyzing, is CaptureUi.Countdown, is CaptureUi.Recording, is CaptureUi.Practice -> Unit
        is CaptureUi.Done -> Column {
            onDoneContent(c.attemptId)
            TextButton(onClick = { vm.dismissCapture() }) { Text("Dismiss") }
        }
        is CaptureUi.Problem -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Row(Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.message, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.dismissCapture() }) { Text("OK") }
            }
        }
    }
}



/** True while a capture shows in the sheet (the countdown, the recording, its analysis and a practice run). */
internal fun CaptureUi.showsSheet(): Boolean = when (this) {
    is CaptureUi.Countdown, is CaptureUi.Recording, CaptureUi.Analyzing, is CaptureUi.Practice -> true
    CaptureUi.Idle, is CaptureUi.Done, is CaptureUi.Problem -> false
}

/** The capture sheet never settles in the hidden state: no swipe, no tap on the scrim, no back gesture closes it. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun captureSheetMayChange(target: SheetValue): Boolean = target != SheetValue.Hidden

/**
 * A modal sheet over the whole screen for as long as a capture runs, so the start of a recording cannot be
 * missed after scrolling and nothing underneath can be touched meanwhile. It cannot be swiped, tapped or backed
 * away; it ends with the capture, or with its own Cancel / Stop button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(vm: AppViewModel) {
    val c = vm.capture
    if (!c.showsSheet()) return
    // A buzz marks the end of the recording: moving or picking up the phone before it spoils the tail.
    val haptic = LocalHapticFeedback.current
    val recordingEnded = c is CaptureUi.Analyzing
    LaunchedEffect(recordingEnded) { if (recordingEnded) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = ::captureSheetMayChange),
        sheetGesturesEnabled = false,
        dragHandle = null,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 24.dp)) {
            ActiveCapture(vm, c)
        }
    }
}

/** Live prompts during a capture. */
@Composable
private fun ActiveCapture(vm: AppViewModel, c: CaptureUi) {
    when (c) {
        is CaptureUi.Countdown -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Get ready… ${c.secondsLeft}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Put the phone down, move to your clap spot and keep still. The microphone is off until the countdown ends, " +
                        "so the button tap is not recorded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { vm.cancelCapture() }) { Text("Cancel") }
            }
        }
        is CaptureUi.Recording -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val (headline, detail) = when (c.phase) {
                    "QUIET" -> "Stay quiet…" to "Recording the background first. Keep still."
                    "CLAP" -> "Clap once now" to "One crisp clap at your marked spot, about as firm as applause. Not your hardest: watch the level below."
                    "LISTEN" -> "Listening to the decay…" to "Stay still and quiet until recording ends."
                    else -> "Recording background…" to "Keep the room quiet and still."
                }
                // Read out by a screen reader when the prompt changes ("Clap once now").
                Text(
                    headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                val left = ceil(c.total - c.elapsed).toInt().coerceAtLeast(0)
                Text("Stay still until the phone buzzes (about $left s).", style = MaterialTheme.typography.bodySmall)
                LevelMeter(c)
                LinearProgressIndicator(progress = { (c.elapsed / c.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { vm.cancelCapture() }) { Text("Cancel") }
            }
        }
        is CaptureUi.Practice -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (c.finished) "Practice finished" else "Practice clap level",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                )
                if (!c.finished) {
                    Text(
                        "Stand at your clap spot and clap once every few seconds. Adjust distance or strength until the last clap " +
                            "reads Good level. Nothing is saved.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { ((c.levelDbfs + 60.0) / 60.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text("Level now: ${fmt(c.levelDbfs, 0)} dBFS · ${fmt((c.total - c.elapsed).coerceAtLeast(0.0), 0)} s left", style = MaterialTheme.typography.bodySmall)
                }
                if (c.claps > 0) {
                    ClapFeedbackText("Last clap:", c.lastClapDbfs)
                    Text("${c.claps} clap(s) detected. Aim for ${fmt(TOO_QUIET_DBFS, 0)} to ${fmt(TOO_LOUD_DBFS, 0)} dBFS.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("No clap detected yet.", style = MaterialTheme.typography.bodySmall)
                }
                if (c.finished) {
                    Text(
                        "Note the distance in the position's placement notes and keep it the same in every session.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { vm.dismissCapture() }) { Text("Done") }
                } else {
                    OutlinedButton(onClick = { vm.cancelCapture() }) { Text("Stop") }
                }
            }
        }
        CaptureUi.Analyzing -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Saving and analysing…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("The recording has ended. You can move again.", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        else -> Unit
    }
}

/** Live input level (peak, dBFS) and, after the clap prompt, the loudest level so far with plain-language feedback. */
@Composable
private fun LevelMeter(c: CaptureUi.Recording) {
    // −60…0 dBFS mapped to the bar.
    LinearProgressIndicator(
        progress = { ((c.levelDbfs + 60.0) / 60.0).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiary,
    )
    Text("Level now: ${fmt(c.levelDbfs, 0)} dBFS (relative to digital full scale)", style = MaterialTheme.typography.bodySmall)
    if (c.kind == AttemptKind.IMPULSE && c.clapPeakDbfs > -100.0) {
        ClapFeedbackText("Loudest so far:", c.clapPeakDbfs)
        Text("Aim for ${fmt(TOO_QUIET_DBFS, 0)} to ${fmt(TOO_LOUD_DBFS, 0)} dBFS.", style = MaterialTheme.typography.bodySmall)
    }
}
