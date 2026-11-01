package se.klang.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.Screen
import se.klang.app.Sheet
import se.klang.app.ui.theme.ValidColor
import se.klang.app.ui.ScreenPadding
import se.klang.app.ui.SectionCard
import se.klang.app.ui.RecordingSparkline
import se.klang.app.ui.fmt
import se.klang.app.ui.keepUnits
import se.klang.app.ui.rememberWithMicPermission
import se.klang.dsp.AnalysisConfiguration
import se.klang.sessions.AttemptKind
import se.klang.sessions.AttemptReportPolicy
import se.klang.sessions.AttemptVerdict
import se.klang.sessions.CaptureAttempt
import se.klang.sessions.FindingKind
import se.klang.sessions.RecordingTimeline

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

    val summary = remember(sessionId, rev) { vm.sessionSummary(sessionId) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (session.archived) item {
            ArchivedBanner("This session is archived: hidden from the session list and comparisons, with its recordings kept.") {
                vm.setSessionArchived(sessionId, false)
            }
        }
        // A brief summary; the diagrams, target, hints and notes are on their own screen.
        if (summary != null && summary.positions.isNotEmpty()) item(key = "summary") {
            SessionBriefCard(summary) { vm.navigate(Screen.SessionSummary(sessionId)) }
        }
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
                if (stale > 0) {
                    FilledTonalButton(onClick = { vm.reanalyze(sessionId) }, enabled = !vm.reanalyzing) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Analyse $stale recording(s) with algorithm ${AnalysisConfiguration.VERSION}")
                    }
                }
                vm.reanalysisError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                CapturePanel(vm) { id -> attempts.firstOrNull { it.id == id }?.let { LastAttemptSummary(vm, it) } }
                OutlinedCard(onClick = { vm.sheet = Sheet.HowToClap }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        headlineContent = { Text("How to clap") },
                        supportingContent = { Text("Tips and practice clap level") },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (room.positions.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "This room has no positions yet. Positions belong to the room, so every session measures the same spots.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = { vm.sheet = Sheet.AddPosition(room.id) }, modifier = Modifier.align(Alignment.End)) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text("Add position")
                            }
                        }
                    }
                }
            }
        }
        itemsIndexed(room.positions, key = { _, p -> p.id }) { index, p ->
            val here = attempts.filter { it.positionId == p.id }
            val impulses = here.filter { it.kind == AttemptKind.IMPULSE }
            val backgrounds = here.filter { it.kind == AttemptKind.BACKGROUND }
            val usable = impulses.count { vm.report(it).verdict.let { v -> v == AttemptVerdict.USABLE || v == AttemptVerdict.USABLE_WITH_CAUTION } }
            val done = usable >= TARGET_ATTEMPTS && backgrounds.isNotEmpty()
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    leadingContent = {
                        if (done) NumberBadge("", ValidColor, Color.White, done = true) else NumberBadge("${index + 1}")
                    },
                    headlineContent = { Text(p.name) },
                    supportingContent = {
                        Column {
                            Text(if (p.clapSpot.isNotBlank()) "Clap spot: ${p.clapSpot}" else "No clap spot noted")
                            if (p.placementNotes.isNotBlank()) Text("Phone: ${p.placementNotes}")
                        }
                    },
                    trailingContent = {
                        IconButton(enabled = !vm.editingBlocked, onClick = { vm.sheet = Sheet.EditPosition(room.id, p.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit position ${p.name}")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        (if (backgrounds.isEmpty()) "No background yet" else "Background recorded") +
                            " · $usable of $TARGET_ATTEMPTS usable claps" +
                            if (impulses.size > usable) " (${impulses.size} attempts)" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { pending = p.id to AttemptKind.BACKGROUND; start() }) { Text("Background") }
                        Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = { pending = p.id to AttemptKind.IMPULSE; start() }) { Text("Record clap") }
                    }
                }
                RecordingsList(vm, impulses, backgrounds) { vm.navigate(Screen.AttemptDetail(sessionId, it.id)) }
            }
        }
    }
}

/**
 * A position's recordings: each clap with its verdict, why it failed or its T20 values, and a small graph of
 * the recording; then the backgrounds. The rows sit outside any padded column so each gets the standard
 * Material list-item insets and the dividers line up with the text.
 */
@Composable
private fun RecordingsList(vm: AppViewModel, impulses: List<CaptureAttempt>, backgrounds: List<CaptureAttempt>, onOpen: (CaptureAttempt) -> Unit) {
    if (impulses.isEmpty() && backgrounds.isEmpty()) return
    Column {
        Text(
            "Recordings",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        (impulses + backgrounds).forEachIndexed { i, a ->
            // No divider above the first item: Material lists divide between items only.
            if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp))
            RecordingRow(vm, a) { onOpen(a) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * A recording as a list item: a verdict icon, "Clap 3 · Usable", one line (why not, or the T20 values) and the
 * level of the recording as a small graph. Invalid bands give no number: the line is the reason.
 */
@Composable
private fun RecordingRow(vm: AppViewModel, a: CaptureAttempt, onClick: () -> Unit) {
    val background = a.kind == AttemptKind.BACKGROUND
    val timeline by produceState<RecordingTimeline?>(null, a.id, a.analyses.size) { value = vm.timeline(a) }
    val report = remember(a, timeline) { vm.report(a, timeline) }
    val color = if (background) MaterialTheme.colorScheme.outline else verdictColor(report.verdict)
    val title = if (background) "Background ${a.sequence}" else "Clap ${a.sequence}"
    val word = if (background) null else verdictWord(report.verdict)
    val line = if (background) {
        listOfNotNull(timeline?.backgroundDbfs?.let { "Room noise ${fmt(it, 0)} dBFS".replace('-', '−') }, if (a.captureEvents.isEmpty()) "no capture events" else "${a.captureEvents.size} capture event(s)")
            .joinToString(" · ")
    } else report.oneLine
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title${word?.let { ", $it" } ?: ""}. $line" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (background) Icons.Outlined.Info else verdictIcon(report.verdict), contentDescription = null,
                tint = color, modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (word != null) Text("· $word", style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = FontWeight.Medium)
            }
            Text(line.keepUnits(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            timeline?.let { RecordingSparkline(it, hot = !background && it.peakDbfs >= AttemptReportPolicy.GOOD_CLAP_MAX_DBFS) } ?: Spacer(Modifier.height(32.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

@Composable
private fun LastAttemptSummary(vm: AppViewModel, a: CaptureAttempt) {
    if (a.kind == AttemptKind.BACKGROUND) {
        SectionCard("Background saved") { Text("Background recording stored with its capture configuration.") }
        return
    }
    val report = vm.report(a)
    val color = verdictColor(report.verdict)
    SectionCard("Clap #${a.sequence} saved") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(verdictIcon(report.verdict), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(report.headline.keepUnits(), style = MaterialTheme.typography.titleMedium, color = color)
        }
        report.findings.filter { it.kind != FindingKind.OK }.forEach { f ->
            Text(f.title.keepUnits(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            f.advice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        report.t20Line?.let { Text(it.keepUnits(), style = MaterialTheme.typography.bodySmall) }
        Text("Open the recording in the list for the graph. Rejected attempts are kept for inspection and can be deleted there.", style = MaterialTheme.typography.bodySmall)
    }
}
