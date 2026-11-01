package se.klang.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.CaptureUi
import se.klang.app.ui.fmt
import se.klang.sessions.AttemptKind
import kotlin.math.ceil

/** Live prompts during a capture and its immediate outcome. */
@Composable
fun CapturePanel(vm: AppViewModel, onDoneContent: @Composable (attemptId: String) -> Unit = {}) {
    // A buzz marks the end of the recording: moving or picking up the phone before it spoils the tail.
    val haptic = LocalHapticFeedback.current
    val recordingEnded = vm.capture is CaptureUi.Analyzing
    LaunchedEffect(recordingEnded) { if (recordingEnded) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    when (val c = vm.capture) {
        CaptureUi.Idle -> Unit
        is CaptureUi.Countdown -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Get ready… ${c.secondsLeft}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Put the phone down, move to your clap spot and keep still. The microphone is off until the countdown ends, " +
                        "so the button tap is not recorded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { vm.cancelCapture() }) { Text("Cancel") }
            }
        }
        is CaptureUi.Recording -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val (headline, detail) = when (c.phase) {
                    "QUIET" -> "Stay quiet…" to "Recording the background first. Keep still."
                    "CLAP" -> "Clap once now" to "One crisp clap at your marked spot, about as firm as applause. Not your hardest: watch the level below."
                    "LISTEN" -> "Listening to the decay…" to "Stay still and quiet until recording ends."
                    else -> "Recording background…" to "Keep the room quiet and still."
                }
                Text(headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                val left = ceil(c.total - c.elapsed).toInt().coerceAtLeast(0)
                Text("Stay still until the phone buzzes (about $left s).", style = MaterialTheme.typography.bodySmall)
                LevelMeter(c)
                LinearProgressIndicator(progress = { (c.elapsed / c.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { vm.cancelCapture() }) { Text("Cancel") }
            }
        }
        is CaptureUi.Practice -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        CaptureUi.Analyzing -> Text("Saving and analysing…", modifier = Modifier.padding(vertical = 4.dp))
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
