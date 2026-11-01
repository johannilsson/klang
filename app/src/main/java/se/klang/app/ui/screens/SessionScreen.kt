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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.Screen
import se.klang.app.Sheet
import se.klang.app.ui.theme.ValidColor
import se.klang.app.ui.ScreenPadding
import se.klang.app.ui.SectionCard
import se.klang.app.ui.StateChip
import se.klang.app.ui.advice
import se.klang.app.ui.fmt
import se.klang.app.ui.stateColor
import se.klang.app.ui.stateLabel
import se.klang.app.ui.rememberWithMicPermission
import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.QualityState
import se.klang.sessions.AttemptKind
import se.klang.sessions.BandRecord
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

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                    FilledTonalButton(onClick = { vm.reanalyze(sessionId) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Analyse $stale recording(s) with algorithm ${AnalysisConfiguration.VERSION}")
                    }
                }
                CapturePanel(vm) { id -> attempts.firstOrNull { it.id == id }?.let { LastAttemptSummary(it) } }
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
            val usable = impulses.count { a -> a.latestAnalysis()?.bands?.any { it.centerHz in setOf(500, 1000, 2000) && it.t20 != null } == true }
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
                // Recordings sit outside the padded column so each row gets the standard
                // Material list-item insets (16 dp sides) and dividers line up with the text.
                if (impulses.isNotEmpty() || backgrounds.isNotEmpty()) {
                    // No divider above the first item: Material lists divide between items only.
                    Text(
                        "Recordings · T20 in seconds per octave band",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                    impulses.forEachIndexed { i, a ->
                        if (i > 0) RecordingDivider()
                        AttemptRow(a) { vm.navigate(Screen.AttemptDetail(sessionId, a.id)) }
                    }
                    backgrounds.forEachIndexed { i, a ->
                        if (i > 0 || impulses.isNotEmpty()) RecordingDivider()
                        BackgroundRow(a) { vm.navigate(Screen.AttemptDetail(sessionId, a.id)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Divider inset to the list text: 16 dp padding + 40 dp leading badge + 16 dp gap. */
@Composable
private fun RecordingDivider() = HorizontalDivider(Modifier.padding(start = 72.dp))

/** Leading 40 dp badge for a recording, tinted by its quality state (grey when not analysed). */
@Composable
private fun RecordingBadge(text: String, state: QualityState?) {
    val tint = state?.let { stateColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(40.dp).background(tint.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/**
 * A clap attempt as a Material three-line list item: 16 dp side padding, 12 dp vertical padding,
 * 88 dp minimum height. Bands show their T20 value with a state dot; invalid bands show no number.
 */
@Composable
private fun AttemptRow(a: CaptureAttempt, onClick: () -> Unit) {
    val an = a.latestAnalysis()
    // Same insets and text styles as a three-line Material list item, but with the badge and chevron
    // centred vertically (ListItem top-aligns them in three-line rows).
    Row(
        Modifier.fillMaxWidth().heightIn(min = 88.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RecordingBadge("${a.sequence}", an?.state)
        Column(Modifier.weight(1f)) {
            Text(
                if (an == null) "Clap ${a.sequence} · not analysed" else "Clap ${a.sequence} · ${stateLabel(an.state)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (an != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    an.bands.forEach { b -> BandCell(b, Modifier.weight(1f)) }
                }
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BandCell(b: BandRecord, modifier: Modifier = Modifier) {
    val value = if (b.state == QualityState.INVALID) null else b.t20?.seconds
    val description = "${b.centerHz} Hz, ${stateLabel(b.state)}" + (value?.let { ", ${fmt(it)} seconds" } ?: "")
    Column(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(if (b.centerHz >= 1000) "${b.centerHz / 1000}k" else "${b.centerHz}", style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(6.dp).background(stateColor(b.state), CircleShape))
            Text(fmt(value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

/** A background recording as a two-line list item (72 dp). */
@Composable
private fun BackgroundRow(a: CaptureAttempt, onClick: () -> Unit) {
    val events = a.captureEvents.size
    ListItem(
        leadingContent = { RecordingBadge("${a.sequence}", null) },
        headlineContent = { Text("Background ${a.sequence}") },
        supportingContent = {
            Text(
                if (events == 0) "No capture events" else "$events capture event(s)",
                color = if (events > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
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
        Text("Rejected attempts are kept for inspection; you can add a note or delete them from the attempt page.", style = MaterialTheme.typography.bodySmall)
    }
}
