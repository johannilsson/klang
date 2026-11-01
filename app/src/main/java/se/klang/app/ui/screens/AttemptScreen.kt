package se.klang.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.ui.DecayPlot
import se.klang.app.ui.ScreenPadding
import se.klang.app.ui.SectionCard
import se.klang.app.ui.StateChip
import se.klang.app.ui.advice
import se.klang.app.ui.fmt
import se.klang.sessions.AttemptKind
import java.text.DateFormat
import java.util.Date

@Composable
fun AttemptScreen(vm: AppViewModel, sessionId: String, attemptId: String, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val a = vm.attempts(sessionId).firstOrNull { it.id == attemptId } ?: return
    val an = a.latestAnalysis()
    var note by remember(attemptId) { mutableStateOf(a.note) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(if (a.kind == AttemptKind.BACKGROUND) "Background recording #${a.sequence}" else "Recording #${a.sequence}") {
                Text(DateFormat.getDateTimeInstance().format(Date(a.createdAtEpochMs)))
                a.audioConfiguration?.let { c ->
                    Text("${c.audioSource}${if (c.sourceFallback) " (fallback, device processing may be active)" else ""} · ${c.actualSampleRate} Hz ${c.encoding}")
                    Text("Input: ${c.inputDevice?.let { "${it.type} ${it.productName}" } ?: "unknown"}")
                }
                a.captureEvents.forEach { Text("⚠ ${AppViewModel.describeEvent(it)}") }
                if (an != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("Attempt"); StateChip(an.state) }
                    Text("Algorithm ${an.algorithmId} ${an.algorithmVersion} · ${a.analyses.size} analysis run(s) kept")
                    Text("Peak ${fmt(an.peakDbfs, 1)} dBFS · background ${fmt(an.backgroundRmsDbfs, 1)} dBFS RMS · onset ${fmt(an.onsetSeconds)} s")
                    Text("dBFS = relative to digital full scale, not sound pressure level.", style = MaterialTheme.typography.bodySmall)
                    an.reasons.forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (an != null) items(an.bands, key = { it.centerHz }) { b ->
            SectionCard("${b.centerHz} Hz octave${if (b.extended) " (extended band)" else ""}") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StateChip(b.state)
                    Text("T20 ${fmt(b.t20?.seconds)} s", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "EDT ${fmt(b.edt?.seconds)} s (secondary, not yet validated: 0…−10 dB fit, extrapolated to 60 dB)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Margin ${fmt(b.marginDb, 1)} dB (needs 35) · truncated at ${fmt(b.truncationSeconds)} s", style = MaterialTheme.typography.bodySmall)
                b.t20?.let { Text("Fit ${fmt(it.startSeconds, 3)}–${fmt(it.endSeconds, 3)} s, ${fmt(it.slopeDbPerSecond, 1)} dB/s, ξ ${fmt(it.nonLinearityPermille, 1)} ‰", style = MaterialTheme.typography.bodySmall) }
                b.reasons.filter { r -> an.reasons.none { it.code == r.code } }.forEach { r ->
                    Text("• ${r.message}", style = MaterialTheme.typography.bodySmall)
                    advice(r.code)?.let { Text("   $it", style = MaterialTheme.typography.bodySmall) }
                }
                b.curve?.let { DecayPlot(it, b.t20) }
            }
        }
        item {
            SectionCard("Note") {
                OutlinedTextField(
                    note, { note = it },
                    placeholder = { Text("e.g. door slammed during the decay") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = note.trim() != a.note, onClick = { vm.updateAttemptNote(a, note.trim()) }) { Text("Save note") }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { vm.askDeleteAttempt(a) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(if (a.kind == AttemptKind.BACKGROUND) "Delete this background recording" else "Delete this attempt and its recording") }
        }
    }
}
