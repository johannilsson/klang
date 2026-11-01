package se.klang.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.klang.app.AppViewModel
import se.klang.app.Sheet
import se.klang.app.ui.ScreenPadding
import se.klang.app.ui.fmt
import se.klang.app.ui.keepUnits
import se.klang.app.ui.theme.BaselineColor
import se.klang.app.ui.theme.TreatmentColor
import se.klang.app.ui.theme.ValidColor
import kotlin.math.ceil
import se.klang.sessions.ChangeBand
import se.klang.sessions.ComparisonText
import se.klang.sessions.PositionSummary
import se.klang.sessions.ProfileWording
import se.klang.sessions.ReferenceProfile
import se.klang.sessions.SessionSummary
import se.klang.sessions.SummaryBand
import se.klang.sessions.TargetItem
import se.klang.sessions.TargetStatus
import se.klang.sessions.Verdict

/*
 * Plain-language summary cards (#26). Every text comes from core's Summarizer (versioned rules),
 * so the screen shows exactly what an export records.
 */

/** The compact card on the session screen: one sentence per position and a link to the details. */
@Composable
fun SessionBriefCard(summary: SessionSummary, onOpenDetails: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("IN PLAIN WORDS", style = MaterialTheme.typography.labelMedium)
            summary.positions.forEach { Text(it.brief.keepUnits(), style = MaterialTheme.typography.bodyLarge) }
            TextButton(onClick = onOpenDetails, modifier = Modifier.align(Alignment.End)) {
                Text("See the details")
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            }
        }
    }
}

/** The detailed summary of a session on its own screen, opened from the brief card. */
@Composable
fun SessionSummaryScreen(vm: AppViewModel, sessionId: String, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val session = vm.session(sessionId) ?: return
    val room = vm.room(session.roomId) ?: return
    val summary = remember(sessionId, rev) { vm.sessionSummary(sessionId) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(session.name, style = MaterialTheme.typography.titleMedium)
        }
        if (summary == null || summary.positions.isEmpty()) item {
            Text("Nothing to summarise yet: record claps at a position first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else item {
            SessionSummaryCards(summary, room.target?.resolve()) { vm.sheet = Sheet.Target(room.id) }
        }
    }
}

/** The detailed summary: one card per measured position with its band diagram, the target, hints and notes. */
@Composable
fun SessionSummaryCards(summary: SessionSummary, profile: ReferenceProfile?, onOpenTarget: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        summary.positions.forEach { PositionSummaryCard(it) }
        val withTarget = summary.positions.filter { it.target != null }
        if (profile == null) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No target set", style = MaterialTheme.typography.titleMedium)
                    Text("Set one under Room › Target to see how each position compares.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOpenTarget, modifier = Modifier.align(Alignment.End)) { Text("Set a target") }
                }
            }
        } else if (withTarget.isNotEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Against ${withTarget.first().target!!.label}", style = MaterialTheme.typography.titleMedium)
                    withTarget.forEach { p ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.target!!.text.keepUnits(), style = MaterialTheme.typography.bodyMedium)
                            p.target!!.evaluation.items.firstOrNull { it.bandHz == null && it.status != null }?.let { TargetScale(p.positionName, it) }
                        }
                    }
                    Text(ProfileWording.footnote(profile).keepUnits(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onOpenTarget, modifier = Modifier.align(Alignment.End)) { Text("Change target") }
                }
            }
        }
        if (summary.hints.isNotEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What usually helps here", style = MaterialTheme.typography.titleMedium)
                    summary.hints.forEach { Text("• ${it.keepUnits()}", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        Notes(summary.notes + "Summary rules ${summary.policyVersion}, algorithm ${summary.algorithmVersion}.")
    }
}

/**
 * Where a position sits against its target: a scale from 0 s, the target zone shaded, the position marked.
 * Only drawn for an evaluated item, so a position that cannot be compared (an invalid band) has no marker.
 */
@Composable
private fun TargetScale(position: String, item: TargetItem) {
    val measured = item.measuredSeconds ?: return
    val upper = item.upperSeconds ?: return
    val lower = item.lowerSeconds
    val max = ceil(maxOf(measured, upper) * 1.25 / 0.2) * 0.2
    val measurer = rememberTextMeasurer()
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val zone = ValidColor.copy(alpha = 0.35f)
    val tick = ValidColor
    val marker = MaterialTheme.colorScheme.primary
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    val state = when (item.status) {
        TargetStatus.ABOVE -> "above the target"
        TargetStatus.BELOW -> "below the target range"
        else -> "within the target"
    }
    val description = "Scale from 0 to ${fmt(max, 1)} seconds. Target zone ${lower?.let { "from ${fmt(it)} " } ?: "up "}to ${fmt(upper)} seconds. " +
        "$position marked at ${fmt(measured)} seconds, $state."
    Canvas(Modifier.fillMaxWidth().height(64.dp).clearAndSetSemantics { contentDescription = description }) {
        val label = TextStyle(fontSize = 11.sp, color = text)
        val bold = TextStyle(fontSize = 12.sp, color = marker, fontWeight = FontWeight.Medium)
        fun x(v: Double) = (v / max * size.width).toFloat()
        fun draw(textValue: String, style: TextStyle, center: Float, top: Float) {
            val w = measurer.measure(textValue, style).size.width
            drawText(measurer, textValue, Offset((center - w / 2f).coerceIn(0f, size.width - w), top), style)
        }
        val mid = 30.dp.toPx()
        drawRoundRect(track, Offset(0f, mid - 4.dp.toPx()), Size(size.width, 8.dp.toPx()), CornerRadius(4.dp.toPx()))
        val zoneLeft = lower?.let { x(it) } ?: 0f
        drawRoundRect(zone, Offset(zoneLeft, mid - 8.dp.toPx()), Size(x(upper) - zoneLeft, 16.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawLine(tick, Offset(x(upper), mid - 12.dp.toPx()), Offset(x(upper), mid + 12.dp.toPx()), strokeWidth = 2.dp.toPx())
        drawRoundRect(marker, Offset(x(measured) - 2.dp.toPx(), mid - 14.dp.toPx()), Size(4.dp.toPx(), 28.dp.toPx()), CornerRadius(2.dp.toPx()))
        draw("$position ${fmt(measured)} s", bold, x(measured), 0f)
        draw("0 s", label, 0f, mid + 18.dp.toPx())
        draw("target ${fmt(upper)} s", label, x(upper), mid + 18.dp.toPx())
        draw("${fmt(max, 1)} s", label, size.width, mid + 18.dp.toPx())
    }
}

@Composable
private fun PositionSummaryCard(p: PositionSummary) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("In plain words · ${p.positionName}".uppercase(), style = MaterialTheme.typography.labelMedium)
            Text(p.headline.keepUnits(), style = MaterialTheme.typography.titleLarge)
            p.shape?.let { Text(it.keepUnits(), style = MaterialTheme.typography.bodyMedium) }
            p.unmeasured.forEach { Text(it.keepUnits(), style = MaterialTheme.typography.bodyMedium) }
            p.questionable?.let { Text(it.keepUnits(), style = MaterialTheme.typography.bodyMedium) }
            if (p.bands.any { it.medianSeconds != null }) {
                BandBars(p.positionName, p.bands)
                Text(
                    "Seconds for the sound to fade by 60 dB (T20), median of the usable claps, per octave band in Hz.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(p.quality.keepUnits(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            p.advice?.let { Text(it.keepUnits(), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** The toggle that opens the numbers behind a card; it sits on the card it belongs to. */
@Composable
private fun NumbersToggle(open: Boolean, showLabel: String, hideLabel: String, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(if (open) hideLabel else showLabel)
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize),
        )
    }
}

/** The numbers inside a card: on a plain surface so their text keeps its normal contrast on the tinted card. */
@Composable
private fun NumbersPanel(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        content = content,
    )
}

/** Bars per band; a band without a value is drawn as an empty dashed outline with "not measured". */
@Composable
private fun BandBars(position: String, bands: List<SummaryBand>) {
    val max = bands.mapNotNull { it.medianSeconds }.maxOrNull()?.coerceAtLeast(0.1) ?: return
    val description = "Reverberation per octave band at $position. " + bands.joinToString("; ") { b ->
        "${b.bandHz} Hz " + (b.medianSeconds?.let { "${fmt(it)} s" + if (b.questionable) " (questionable)" else "" } ?: "not measured")
    } + "."
    val barMax = 88.dp
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bands.forEach { b ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val v = b.medianSeconds
                Text(
                    if (v == null) "not\nmeasured" else fmt(v) + if (b.questionable) "?" else "",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
                val shape = RoundedCornerShape(6.dp)
                if (v == null) {
                    Box(Modifier.fillMaxWidth().height(barMax * 0.6f).border(1.5.dp, MaterialTheme.colorScheme.outline, shape))
                } else {
                    Box(Modifier.fillMaxWidth().height(barMax * (v / max).toFloat()).background(MaterialTheme.colorScheme.primary, shape))
                }
                Text(if (b.bandHz >= 1000) "${b.bandHz / 1000}k" else "${b.bandHz}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** The comparison summary: headline, one row per position, next steps, target and notes. */
@Composable
fun ComparisonSummaryCards(
    text: ComparisonText,
    /** The numbers behind one position (plot and every band), shown when that position's toggle is opened. */
    numbers: @Composable (positionId: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("In plain words".uppercase(), style = MaterialTheme.typography.labelMedium)
                Text(text.headline.keepUnits(), style = MaterialTheme.typography.titleLarge)
                text.variation?.let { Text(it.keepUnits(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (text.positions.isNotEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    text.positions.forEach { p ->
                        var open by rememberSaveable(p.positionId) { mutableStateOf(false) }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.positionName, style = MaterialTheme.typography.titleMedium)
                            if (p.beforeSeconds != null && p.afterSeconds != null) {
                                Text("Speech range ${fmt(p.beforeSeconds)} s → ${fmt(p.afterSeconds)} s", style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                p.statement.keepUnits(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (p.verdict == Verdict.DECREASE_EXCEEDS_VARIABILITY || p.verdict == Verdict.INCREASE_EXCEEDS_VARIABILITY) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (p.bands.any { it.beforeSeconds != null || it.afterSeconds != null }) {
                                Spacer(Modifier.height(6.dp))
                                ChangeBars(p.positionName, p.bands)
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LegendDot(BaselineColor, "Before")
                                    LegendDot(TreatmentColor, "After")
                                    Text("seconds, per octave band in Hz", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            p.where?.let { Text(it.keepUnits(), style = MaterialTheme.typography.bodySmall) }
                            NumbersToggle(open, "Show every band and attempt", "Hide every band and attempt") { open = !open }
                            if (open) NumbersPanel { numbers(p.positionId) }
                        }
                    }
                }
            }
        }
        if (text.nextSteps.isNotEmpty() || text.target.isNotEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Next step", style = MaterialTheme.typography.titleMedium)
                    (text.target + text.nextSteps).forEach { Text("• ${it.keepUnits()}", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        Notes(text.notes + "Summary rules ${text.policyVersion}, comparison policy ${text.comparisonPolicyVersion}, algorithm ${text.algorithmVersion}.")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Before and after per band as paired bars; a band without a value is an empty dashed outline ("not measured"). */
@Composable
private fun ChangeBars(position: String, bands: List<ChangeBand>) {
    val max = bands.flatMap { listOfNotNull(it.beforeSeconds, it.afterSeconds) }.maxOrNull()?.coerceAtLeast(0.1) ?: return
    val description = "$position before and after, per band. " + bands.joinToString(" ") { b ->
        "${b.bandHz} Hz " + if (b.beforeSeconds == null && b.afterSeconds == null) "not measured."
        else "${b.beforeSeconds?.let { fmt(it) } ?: "no value"} to ${b.afterSeconds?.let { fmt(it) } ?: "no value"} seconds."
    }
    val barMax = 80.dp
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bands.forEach { b ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (b.beforeSeconds == null && b.afterSeconds == null) {
                    Text("not\nmeasured", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    Box(Modifier.fillMaxWidth().height(barMax * 0.6f).border(1.5.dp, MaterialTheme.colorScheme.outline, shape))
                } else {
                    Row(Modifier.fillMaxWidth().height(barMax), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                        listOf(b.beforeSeconds to BaselineColor, b.afterSeconds to TreatmentColor).forEach { (v, color) ->
                            if (v == null) Box(Modifier.weight(1f).height(barMax * 0.3f).border(1.dp, MaterialTheme.colorScheme.outline, shape))
                            else Box(Modifier.weight(1f).height(barMax * (v / max).toFloat()).background(color, shape))
                        }
                    }
                }
                Text(if (b.bandHz >= 1000) "${b.bandHz / 1000}k" else "${b.bandHz}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Notes(notes: List<String>) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        notes.forEach { Text(it.keepUnits(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
