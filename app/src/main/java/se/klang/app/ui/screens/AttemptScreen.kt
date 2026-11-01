package se.klang.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.ui.DecayPlot
import se.klang.app.ui.MarginBar
import se.klang.app.ui.ScreenPadding
import se.klang.app.ui.SectionCard
import se.klang.app.ui.displayedT20
import se.klang.app.ui.fmt
import se.klang.app.ui.keepUnits
import se.klang.app.ui.theme.InvalidColor
import se.klang.app.ui.theme.QuestionableColor
import se.klang.dsp.AnalysisConfiguration
import se.klang.dsp.QualityState
import se.klang.sessions.AnalysisRecord
import se.klang.sessions.AttemptKind
import se.klang.sessions.BandRecord
import se.klang.sessions.CaptureAttempt
import se.klang.sessions.RecordingTimeline
import java.text.DateFormat
import java.util.Date

/**
 * One recording: the verdict, a graph of the recording, each distinct problem once, the bands (each opens to its
 * decay curve) and, folded away, the technical details. The texts come from core's AttemptReporter.
 */
@Composable
fun AttemptScreen(vm: AppViewModel, sessionId: String, attemptId: String, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val a = vm.attempts(sessionId).firstOrNull { it.id == attemptId } ?: return
    val an = vm.analysisFor(a)
    val background = a.kind == AttemptKind.BACKGROUND
    var confirmDelete by remember { mutableStateOf(false) }
    var showTechnical by rememberSaveable { mutableStateOf(false) }
    var openBand by rememberSaveable { mutableIntStateOf(-1) }
    val timeline by produceState<RecordingTimeline?>(null, a.id, a.analyses.size) { value = vm.timeline(a) }
    val levels by produceState<AppViewModel.RecordingLevels?>(null, a.id) { value = vm.recordingLevels(a) }
    val report = remember(a, timeline) { vm.report(a, timeline) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                (if (background) "Background ${a.sequence}" else "Clap ${a.sequence}") + " · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(a.createdAtEpochMs)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (background) {
            item {
                SectionCard("Background recording") {
                    levels?.let { l ->
                        Text("${fmt(l.durationSeconds, 1)} s at ${l.sampleRate} Hz · RMS ${fmt(l.rmsDbfs, 1)} dBFS · peak ${fmt(l.peakDbfs, 1)} dBFS".keepUnits())
                    }
                    Text(
                        "A background recording shows how loud the room is. It is stored with its capture settings and is not analysed.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (a.captureEvents.isNotEmpty()) a.captureEvents.forEach { Text("⚠ ${AppViewModel.describeEvent(it)}") }
                }
            }
            item { RecordingCard(timeline, report, title = "Level of the room") }
        } else {
            item { VerdictCard(report) }
            item { RecordingCard(timeline, report) }
            item { FindingsCard(report) }
            if (an != null) item { BandsCard(an, openBand) { openBand = if (openBand == it) -1 else it } }
        }
        item { TechnicalCard(a, an, showTechnical, levels) { showTechnical = !showTechnical } }
        item {
            OutlinedButton(enabled = !vm.editingBlocked, onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (background) "Delete this background recording" else "Delete this attempt and its recording", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (background) "Delete background recording?" else "Delete attempt?") },
            text = { Text("The original recording and all analyses of it will be removed. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; if (vm.deleteAttempt(a)) vm.back() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

/** One row per band; a band opens to its margin, its fit and its decay curve. An invalid band has no number. */
@Composable
private fun BandsCard(an: AnalysisRecord, openBand: Int, onToggle: (Int) -> Unit) {
    val needed = AnalysisConfiguration().minMarginT20Db
    SectionCard("How long each band rings") {
        Text(
            "T20 in seconds. The bar shows how far the clap stood above the noise in that band; the tick is the ${fmt(needed, 0)} dB needed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        an.bands.forEachIndexed { i, b ->
            if (i >= 0) HorizontalDivider()
            BandRow(b, an, needed, open = openBand == b.centerHz) { onToggle(b.centerHz) }
        }
    }
}

@Composable
private fun BandRow(b: BandRecord, an: AnalysisRecord, needed: Double, open: Boolean, onToggle: () -> Unit) {
    val t20 = displayedT20(b)
    val spoken = "${b.centerHz} Hz, " + (t20?.let { "${fmt(it.seconds)} seconds" + if (b.state == QualityState.QUESTIONABLE) ", questionable" else "" } ?: "not measured")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onToggle).semantics(mergeDescendants = true) { contentDescription = spoken }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.width(72.dp)) {
                Text("${b.centerHz} Hz", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (b.extended) Text("extended", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (t20 != null) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(fmt(t20.seconds), style = MaterialTheme.typography.headlineSmall)
                        Text("s", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    if (b.state == QualityState.QUESTIONABLE) Text("questionable", style = MaterialTheme.typography.labelMedium, color = QuestionableColor)
                } else Row { Tag("Not measured", InvalidColor) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MarginBar(b.marginDb, needed)
                Text(b.marginDb?.let { "${fmt(it, 0)} dB" } ?: "— dB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Hide details" else "Show details")
        }
        if (open) {
            Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Margin ${fmt(b.marginDb, 1)} dB (needs ${fmt(needed, 0)}) · truncated at ${fmt(b.truncationSeconds)} s".keepUnits(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    t20?.let {
                        Text(
                            "Fitted ${fmt(it.startSeconds, 3)}–${fmt(it.endSeconds, 3)} s, ${fmt(it.slopeDbPerSecond, 1)} dB/s, ξ ${fmt(it.nonLinearityPermille, 1)} ‰".keepUnits(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // The reasons that are about this band alone; the ones shared with the whole recording are in "What went wrong".
                    b.reasons.filter { r -> an.reasons.none { it.code == r.code } && r.severity != QualityState.VALID }.map { it.message }.distinct()
                        .forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    b.curve?.let { DecayPlot(it, t20) }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.16f)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Capture settings, algorithm and the secondary EDT, folded away. */
@Composable
private fun TechnicalCard(a: CaptureAttempt, an: AnalysisRecord?, open: Boolean, levels: AppViewModel.RecordingLevels?, onToggle: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 52.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Technical details", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Hide" else "Show")
            }
            if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                a.audioConfiguration?.let { c ->
                    Text("${c.audioSource}${if (c.sourceFallback) " (fallback, device processing may be active)" else ""} · ${c.actualSampleRate} Hz ${c.encoding}".keepUnits(), style = MaterialTheme.typography.bodySmall)
                    Text("Input: ${c.inputDevice?.let { "${it.type} ${it.productName}" } ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                }
                if (a.captureEvents.isEmpty()) Text("No capture events", style = MaterialTheme.typography.bodySmall)
                a.captureEvents.forEach { Text("⚠ ${AppViewModel.describeEvent(it)}", style = MaterialTheme.typography.bodySmall) }
                if (an != null) {
                    Text("Algorithm ${an.algorithmId} ${an.algorithmVersion} · ${a.analyses.size} analysis run(s) kept", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Peak ${fmt(an.peakDbfs, 1)} dBFS · background ${fmt(an.backgroundRmsDbfs, 1)} dBFS RMS · onset ${fmt(an.onsetSeconds)} s".keepUnits(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val edt = an.bands.filter { it.edt != null }
                    if (edt.isNotEmpty()) {
                        Text(
                            "EDT (secondary, not yet validated: 0…−10 dB fit, extrapolated to 60 dB): " +
                                edt.joinToString(" · ") { "${it.centerHz} Hz ${fmt(it.edt!!.seconds)} s" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else levels?.let {
                    Text("${fmt(it.durationSeconds, 1)} s at ${it.sampleRate} Hz · RMS ${fmt(it.rmsDbfs, 1)} dBFS · peak ${fmt(it.peakDbfs, 1)} dBFS".keepUnits(), style = MaterialTheme.typography.bodySmall)
                }
                Text("dBFS = relative to digital full scale, not sound pressure level.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
