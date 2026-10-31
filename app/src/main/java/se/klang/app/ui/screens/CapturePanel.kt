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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.CaptureUi

/** Live prompts during a capture and its immediate outcome. */
@Composable
fun CapturePanel(vm: AppViewModel, onDoneContent: @Composable (attemptId: String) -> Unit = {}) {
    when (val c = vm.capture) {
        CaptureUi.Idle -> Unit
        is CaptureUi.Recording -> Card(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val (headline, detail) = when (c.phase) {
                    "QUIET" -> "Stay quiet…" to "Recording the background first. Keep still."
                    "CLAP" -> "Clap once now" to "One clap at the marked source position, at your usual strength."
                    "LISTEN" -> "Listening to the decay…" to "Stay still and quiet until recording ends."
                    else -> "Recording background…" to "Keep the room quiet and still."
                }
                Text(headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { (c.elapsed / c.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { vm.cancelCapture() }) { Text("Cancel") }
            }
        }
        CaptureUi.Analyzing -> Text("Saving and analysing…", modifier = Modifier.padding(vertical = 8.dp))
        is CaptureUi.Done -> Column {
            onDoneContent(c.attemptId)
            TextButton(onClick = { vm.dismissCapture() }) { Text("Dismiss") }
        }
        is CaptureUi.Problem -> Card(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.message, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.dismissCapture() }) { Text("OK") }
            }
        }
    }
}
