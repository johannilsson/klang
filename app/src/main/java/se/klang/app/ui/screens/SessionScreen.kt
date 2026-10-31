package se.klang.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.CaptureUi
import se.klang.app.Screen
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

    val practice = rememberWithMicPermission(onDenied = { vm.permissionDenied() }) { vm.startPractice() }

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        item {
            SectionCard(kindLabel(session.kind)) {
                if (session.intervention.isNotBlank()) Text("Intervention: ${session.intervention}")
                val c = session.conditions
                listOf("Doors/windows" to c.doorsAndWindows, "Furnishings" to c.furnishings, "Occupancy" to c.occupancy, "Source" to c.sourcePosition)
                    .filter { it.second.isNotBlank() }.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
                c.appliancesOff?.let { Text("Appliances off: ${if (it) "yes" else "no"}", style = MaterialTheme.typography.bodySmall) }
                val stale = attempts.count { it.kind == AttemptKind.IMPULSE && it.analysis(AnalysisConfiguration.VERSION) == null }
                if (stale > 0) TextButton(onClick = { vm.reanalyze(sessionId) }) { Text("Analyse $stale recording(s) with algorithm ${AnalysisConfiguration.VERSION}") }
            }
            CapturePanel(vm) { id -> attempts.firstOrNull { it.id == id }?.let { LastAttemptSummary(it) } }
            HowToClapCard(onPractice = if (busy) null else practice)
            if (room.positions.isEmpty()) Text("Add measurement positions to the room first.", modifier = Modifier.padding(8.dp))
        }
        room.positions.forEach { p ->
            item(key = p.id) {
                val here = attempts.filter { it.positionId == p.id }
                val impulses = here.filter { it.kind == AttemptKind.IMPULSE }
                val backgrounds = here.filter { it.kind == AttemptKind.BACKGROUND }
                val usable = impulses.count { a -> a.latestAnalysis()?.bands?.any { it.centerHz in setOf(500, 1000, 2000) && it.t20 != null } == true }
                SectionCard(p.name) {
                    if (p.placementNotes.isNotBlank()) Text(p.placementNotes, style = MaterialTheme.typography.bodySmall)
                    Text("Background recordings: ${backgrounds.size} · clap attempts: $usable usable of ${impulses.size} (target $TARGET_ATTEMPTS usable)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = { pending = p.id to AttemptKind.BACKGROUND; start() }) { Text("Record background") }
                        Button(enabled = !busy, onClick = { pending = p.id to AttemptKind.IMPULSE; start() }) { Text("Record clap") }
                    }
                    impulses.forEach { a -> AttemptRow(a) { vm.navigate(Screen.AttemptDetail(sessionId, a.id)) } }
                    backgrounds.forEach { a ->
                        Text("Background #${a.sequence}${if (a.captureEvents.isNotEmpty()) " ⚠ ${a.captureEvents.size} capture event(s)" else ""}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
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
