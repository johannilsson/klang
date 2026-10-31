package se.klang.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.CaptureUi
import se.klang.app.Screen
import se.klang.app.Sheet
import se.klang.app.ui.theme.ValidColor
import se.klang.app.ui.SectionCard
import se.klang.app.ui.StateChip
import se.klang.app.ui.advice
import se.klang.app.ui.fmt
import se.klang.app.ui.rememberWithMicPermission
import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.QualityState
import se.klang.sessions.AttemptKind
import se.klang.sessions.CaptureAttempt

private const val TARGET_ATTEMPTS = 5

@Composable
fun SessionScreen(vm: AppViewModel, sessionId: String, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val session = vm.session(sessionId) ?: return
    val room = vm.room(session.roomId) ?: return
    val attempts = vm.attempts(sessionId)
    val busy = vm.isCapturing
    var pending by remember { mutableStateOf<Pair<String, AttemptKind>?>(null) }
    val start = rememberWithMicPermission(onDenied = { vm.permissionDenied() }) {
        pending?.let { (pos, kind) -> vm.record(sessionId, pos, kind) }
    }

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindChip(session.kind)
                    Text(
                        session.intervention,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.sheet = Sheet.Conditions(sessionId) }) { Text("Conditions") }
                }
                val stale = attempts.count { it.kind == AttemptKind.IMPULSE && it.analysis(AnalysisConfiguration.VERSION) == null }
                if (stale > 0) TextButton(onClick = { vm.reanalyze(sessionId) }) { Text("Analyse $stale recording(s) with algorithm ${AnalysisConfiguration.VERSION}") }
                CapturePanel(vm) { id -> attempts.firstOrNull { it.id == id }?.let { LastAttemptSummary(it) } }
                OutlinedCard(onClick = { vm.sheet = Sheet.HowToClap }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("How to clap · practice level", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (room.positions.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "This room has no positions yet. Positions belong to the room, so every session measures the same spots.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = { vm.sheet = Sheet.AddPosition(room.id) }) { Text("Add position to room") }
                        }
                    }
                }
            }
        }
        itemsIndexed(room.positions, key = { _, p -> p.id }) { index, p ->
            val here = attempts.filter { it.positionId == p.id }
            val impulses = here.filter { it.kind == AttemptKind.IMPULSE }
            val backgrounds = here.filter { it.kind == AttemptKind.BACKGROUND }
            val usable = impulses.count { a -> a.latestAnalysis()?.bands?.any { it.centerHz in setOf(500, 1000, 2000) && it.t20 != null } == true }
            val done = usable >= TARGET_ATTEMPTS && backgrounds.isNotEmpty()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (done) NumberBadge("✓", ValidColor, Color.White) else NumberBadge("${index + 1}")
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (p.clapSpot.isNotBlank()) "Clap spot: ${p.clapSpot}" else "No clap spot noted",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (p.placementNotes.isNotBlank()) {
                                Text("Phone: ${p.placementNotes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(
                        (if (backgrounds.isEmpty()) "No background" else "Background ✓") +
                            " · $usable/$TARGET_ATTEMPTS usable claps" +
                            if (impulses.size > usable) " (${impulses.size} attempts)" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { pending = p.id to AttemptKind.BACKGROUND; start() }) { Text("Background") }
                        Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = { pending = p.id to AttemptKind.IMPULSE; start() }) { Text("Record clap") }
                    }
                    impulses.forEach { a -> AttemptRow(a) { vm.navigate(Screen.AttemptDetail(sessionId, a.id)) } }
                    backgrounds.forEach { a ->
                        Text("Background #${a.sequence}${if (a.captureEvents.isNotEmpty()) " ⚠ ${a.captureEvents.size} capture event(s)" else ""}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun AttemptRow(a: CaptureAttempt, onClick: () -> Unit) {
    val an = a.latestAnalysis()
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("#${a.sequence}", modifier = Modifier.padding(end = 4.dp))
        if (an == null) Text("not analysed") else an.bands.forEach { b ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${b.centerHz}", style = MaterialTheme.typography.labelSmall)
                StateChip(b.state)
                Text(fmt(b.t20?.seconds), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LastAttemptSummary(a: CaptureAttempt) {
    val an = a.latestAnalysis()
    SectionCard(if (a.kind == AttemptKind.BACKGROUND) "Background saved" else "Attempt #${a.sequence} saved") {
        if (a.kind == AttemptKind.BACKGROUND) {
            Text("Background recording stored with its capture configuration.")
            return@SectionCard
        }
        if (an == null) { Text("Not analysed."); return@SectionCard }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("Result"); StateChip(an.state) }
        val reasons = (an.reasons + an.bands.filter { it.centerHz in setOf(500, 1000, 2000) }.flatMap { it.reasons })
            .filter { it.severity != QualityState.VALID }.distinctBy { it.code }
        reasons.forEach { r ->
            Text("• ${r.message}", style = MaterialTheme.typography.bodySmall)
            advice(r.code)?.let { Text("   $it", style = MaterialTheme.typography.bodySmall) }
        }
        if (reasons.isEmpty()) Text("All main bands passed the quality checks.")
        Text("Rejected attempts are kept for inspection; you can delete them from the attempt page.", style = MaterialTheme.typography.bodySmall)
    }
}
