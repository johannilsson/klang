package se.klang.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.klang.app.ui.RecordingGraph
import se.klang.app.ui.SectionCard
import se.klang.app.ui.fmt
import se.klang.app.ui.keepUnits
import se.klang.app.ui.theme.InvalidColor
import se.klang.app.ui.theme.QuestionableColor
import se.klang.app.ui.theme.ValidColor
import se.klang.sessions.AttemptReport
import se.klang.sessions.AttemptReportPolicy
import se.klang.sessions.AttemptVerdict
import se.klang.sessions.Finding
import se.klang.sessions.FindingKind
import se.klang.sessions.RecordingTimeline
import se.klang.sessions.ReportChip
import se.klang.sessions.SoundEvent

/*
 * The cards of the attempt screen that the microphone check shares: the result, the recording graph and the
 * findings, all from core's AttemptReporter so the texts are the tested ones.
 */

@Composable
fun verdictColor(v: AttemptVerdict): Color = when (v) {
    AttemptVerdict.USABLE -> ValidColor
    AttemptVerdict.USABLE_WITH_CAUTION -> QuestionableColor
    AttemptVerdict.NOT_USABLE -> InvalidColor
    AttemptVerdict.NOT_ANALYSED -> MaterialTheme.colorScheme.outline
}

fun verdictIcon(v: AttemptVerdict): ImageVector = when (v) {
    AttemptVerdict.USABLE -> Icons.Filled.CheckCircle
    AttemptVerdict.USABLE_WITH_CAUTION -> Icons.Filled.Warning
    AttemptVerdict.NOT_USABLE -> Icons.Filled.Close
    AttemptVerdict.NOT_ANALYSED -> Icons.Filled.Info
}

/** The short word for a verdict in a list. */
fun verdictWord(v: AttemptVerdict): String = when (v) {
    AttemptVerdict.USABLE -> "Usable"
    AttemptVerdict.USABLE_WITH_CAUTION -> "Usable with caution"
    AttemptVerdict.NOT_USABLE -> "Not usable"
    AttemptVerdict.NOT_ANALYSED -> "Not analysed"
}

/** The verdict at the top of a recording: a headline, one sentence and the facts as chips. */
@Composable
fun VerdictCard(report: AttemptReport, modifier: Modifier = Modifier) {
    val c = verdictColor(report.verdict)
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.16f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(verdictIcon(report.verdict), contentDescription = null, tint = c, modifier = Modifier.size(20.dp))
                Text("RESULT", style = MaterialTheme.typography.labelMedium)
            }
            Text(report.headline.keepUnits(), style = MaterialTheme.typography.headlineSmall)
            if (report.summary.isNotBlank()) Text(report.summary.keepUnits(), style = MaterialTheme.typography.bodyMedium)
            if (report.chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                    report.chips.forEach { ChipLabel(it) }
                }
            }
        }
    }
}

@Composable
private fun ChipLabel(chip: ReportChip) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (chip.warn) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = if (chip.warn) BorderStroke(1.5.dp, InvalidColor) else null,
    ) {
        Text(
            chip.text.keepUnits(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (chip.warn) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** What a screen reader says for the graph: the same facts the texts around it give. */
fun graphDescription(timeline: RecordingTimeline, report: AttemptReport): String = buildString {
    append("Level of the ${fmt(timeline.durationSeconds, 0)} second recording. ")
    timeline.backgroundDbfs?.let { append("The background is about ${fmt(it, 0)} dBFS. ") }
    timeline.onsetSeconds?.let { append("The clap at ${fmt(it, 1)} seconds peaks at ${fmt(timeline.peakDbfs, 1)} dBFS. ") }
    for (e in timeline.events) {
        append(
            if (e.kind == SoundEvent.Kind.BEFORE_CLAP) "A sound at ${fmt(e.timeSeconds, 1)} seconds, ${fmt(e.levelDbfs, 0)} dBFS, comes before the clap. "
            else "A later sound at ${fmt(e.timeSeconds, 1)} seconds does not affect the result. ",
        )
    }
    append(report.headline).append('.')
}.replace('-', '−')

/** The recording graph with its caption. [timeline] is null while the file is being read. */
@Composable
fun RecordingCard(timeline: RecordingTimeline?, report: AttemptReport, modifier: Modifier = Modifier, title: String = "Your recording") {
    SectionCard(title, modifier) {
        Text(
            "How loud it was, second by second. dBFS is relative to digital full scale, not sound pressure.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (timeline == null) {
            Text("Reading the recording…", style = MaterialTheme.typography.bodyMedium)
        } else {
            RecordingGraph(timeline, graphDescription(timeline, report), backgroundWarn = report.chips.any { it.warn && it.text.startsWith("Background") })
            Text(
                "Curve: level per 50 ms. Dashed: background. Green band: good clap level (${fmt(AttemptReportPolicy.GOOD_CLAP_MIN_DBFS, 0)} to " +
                    "${fmt(AttemptReportPolicy.GOOD_CLAP_MAX_DBFS, 0)} dBFS peak).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Each distinct problem once with its advice, then what was fine. */
@Composable
fun FindingsCard(report: AttemptReport, modifier: Modifier = Modifier) {
    if (report.findings.isEmpty()) return
    val hasProblem = report.findings.any { it.kind == FindingKind.PROBLEM }
    SectionCard(if (hasProblem) "What went wrong" else "What we checked", modifier) {
        var fineStarted = false
        report.findings.forEach { f ->
            if (f.kind == FindingKind.OK && !fineStarted && hasProblem) {
                fineStarted = true
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            FindingRow(f)
        }
    }
}

@Composable
private fun FindingRow(f: Finding) {
    val color = when (f.kind) {
        FindingKind.PROBLEM -> InvalidColor
        FindingKind.CAUTION -> QuestionableColor
        FindingKind.OK -> ValidColor
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (f.kind == FindingKind.OK) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = when (f.kind) { FindingKind.PROBLEM -> "Problem"; FindingKind.CAUTION -> "Note"; FindingKind.OK -> "Fine" },
            tint = color, modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.title.keepUnits(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            if (f.detail.isNotBlank()) Text(f.detail.keepUnits(), style = MaterialTheme.typography.bodyMedium)
            f.advice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (f.bandsHz.isNotEmpty() && f.kind != FindingKind.OK && !f.title.contains(" Hz")) {
                Text(
                    "Bands: " + f.bandsHz.joinToString(" · ") { if (it >= 1000) "${it / 1000}k" else "$it" } + " Hz",
                    style = MaterialTheme.typography.labelMedium, color = color,
                )
            }
        }
    }
}
