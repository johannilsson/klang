package se.klang.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import se.klang.app.AppViewModel
import se.klang.app.ui.ScreenPadding
import se.klang.app.ui.SectionCard
import se.klang.app.ui.fmt
import se.klang.app.ui.theme.BaselineColor
import se.klang.app.ui.theme.TreatmentColor
import se.klang.sessions.AnalysisCoverage
import se.klang.sessions.ComparisonCell
import se.klang.sessions.Session
import se.klang.sessions.SessionKind
import se.klang.sessions.Verdict

fun verdictLabel(v: Verdict) = when (v) {
    Verdict.DECREASE_EXCEEDS_VARIABILITY -> "Shorter decay — change larger than observed repeat variability"
    Verdict.INCREASE_EXCEEDS_VARIABILITY -> "Longer decay — change larger than observed repeat variability"
    Verdict.INCONCLUSIVE -> "Change is inconclusive"
    Verdict.INSUFFICIENT_DATA -> "Not enough valid attempts"
}

@Composable
fun CompareScreen(vm: AppViewModel, roomId: String, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val room = vm.room(roomId) ?: return
    val sessions = vm.sessions(roomId)
    var baseline by remember { mutableStateOf(sessions.firstOrNull { it.kind == SessionKind.BASELINE }?.id) }
    var repeat by remember { mutableStateOf(sessions.firstOrNull { it.kind == SessionKind.REPEAT_BASELINE }?.id) }
    var treatment by remember { mutableStateOf(sessions.lastOrNull { it.kind == SessionKind.TREATMENT }?.id) }
    val comparison = remember(baseline, repeat, treatment, rev) {
        val b = baseline; val t = treatment
        if (b != null && t != null && b != t) vm.compare(roomId, b, t, repeat) else null
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("Sessions") {
                SessionPicker("Baseline", sessions.filter { it.kind != SessionKind.TREATMENT }, baseline) { baseline = it }
                SessionPicker("Repeat baseline (unchanged room)", sessions.filter { it.kind != SessionKind.TREATMENT && it.id != baseline }, repeat, allowNone = true) { repeat = it }
                SessionPicker("Treatment", sessions.filter { it.kind == SessionKind.TREATMENT }, treatment) { treatment = it }
                Text(
                    "A change is only called out when it is larger than the variability seen between repeated attempts and between " +
                        "the two unchanged-room sessions, and the attempts do not overlap. This is a descriptive rule, not a statistical test; " +
                        "with five attempts per position no population-level claim is made.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (comparison == null) {
            item { Text("Choose a baseline and a treatment session.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            if (comparison.staleAnalyses.isNotEmpty()) item {
                StaleAnalysesCard(comparison.staleAnalyses, comparison.algorithmVersion, vm.reanalyzing) {
                    vm.reanalyze(comparison.staleAnalyses.map { it.sessionId })
                }
            }
            if (comparison.configurationWarnings.isNotEmpty()) item {
                SectionCard(if (comparison.configurationsIncompatible) "Configurations differ — comparison blocked" else "Notes") {
                    comparison.configurationWarnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                val t = sessions.firstOrNull { it.id == treatment }
                SectionCard("Summary") {
                    t?.let { Text("Treatment: ${it.intervention}") }
                    comparison.summary.cellsByVerdict.filter { it.value > 0 }.forEach { (v, n) -> Text("$n × ${verdictLabel(v)}") }
                    Text(comparison.summary.definition, style = MaterialTheme.typography.bodySmall)
                }
            }
            room.positions.forEach { p ->
                val cells = comparison.cells.filter { it.positionId == p.id }
                if (cells.isEmpty()) return@forEach
                item(key = p.id) {
                    SectionCard(p.name) {
                        ComparisonPlot(cells)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("● before (baseline)", color = BaselineColor, style = MaterialTheme.typography.labelSmall)
                            Text("● after (treatment)", color = TreatmentColor, style = MaterialTheme.typography.labelSmall)
                            Text("— median", style = MaterialTheme.typography.labelSmall)
                        }
                        cells.forEach { CellRow(it) }
                    }
                }
            }
            item {
                OutlinedButton(onClick = {
                    scope.launch { context.startActivity(Intent.createChooser(vm.exportRoom(roomId, listOf(comparison)), "Export comparison")) }
                }) { Text("Export with this comparison") }
            }
        }
    }
}

/**
 * Attempts without a result at the current algorithm version count as not analysed; results from
 * different versions are never mixed. Offers a re-analysis, which appends results.
 */
@Composable
private fun StaleAnalysesCard(stale: List<AnalysisCoverage>, version: String, running: Boolean, onReanalyze: () -> Unit) {
    SectionCard("Re-analysis needed") {
        stale.forEach { c ->
            val older = c.otherVersions.entries.joinToString(" and ") { (v, n) -> "$n with algorithm $v" }
            val never = if (c.neverAnalysed > 0) "${c.neverAnalysed} not analysed" else ""
            val detail = listOf(older, never).filter { it.isNotEmpty() }.joinToString("; ")
            Text(
                "${c.missing} of ${c.impulseAttempts} recording(s) in '${c.sessionName}' have no result with algorithm $version" +
                    (if (detail.isNotEmpty()) " ($detail)" else "") + ".",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "They are counted as not analysed below, so affected cells show too few valid attempts. Re-analyse to compare " +
                "with algorithm $version. Recordings and earlier results are kept.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(enabled = !running, onClick = onReanalyze, modifier = Modifier.padding(top = 8.dp)) {
            Text(if (running) "Re-analysing…" else "Re-analyse with algorithm $version")
        }
    }
}

@Composable
private fun SessionPicker(label: String, options: List<Session>, selected: String?, allowNone: Boolean = false, onSelect: (String?) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Column {
        if (allowNone) FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("None") })
        options.forEach { s -> FilterChip(selected = selected == s.id, onClick = { onSelect(s.id) }, label = { Text(s.name) }) }
        if (options.isEmpty()) Text("No sessions of this kind yet.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CellRow(c: ComparisonCell) {
    HorizontalDivider()
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${c.bandHz} Hz${if (c.extendedBand) " (extended)" else ""}", fontWeight = FontWeight.Bold)
        Text(
            "Before: median ${fmt(c.baseline.medianSeconds)} s, IQR ${fmt(c.baseline.iqrSeconds)} s, ${c.baseline.usable}/${c.baseline.attempted} usable",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "After: median ${fmt(c.treatment.medianSeconds)} s, IQR ${fmt(c.treatment.iqrSeconds)} s, ${c.treatment.usable}/${c.treatment.attempted} usable",
            style = MaterialTheme.typography.bodySmall,
        )
        c.repeatBaseline?.let { Text("Repeat baseline: median ${fmt(it.medianSeconds)} s, ${it.usable}/${it.attempted} usable", style = MaterialTheme.typography.bodySmall) }
        Text(
            "Change ${fmt(c.changeSeconds)} s" + (c.percentReduction?.let { " · reduction ${fmt(it, 0)} %" } ?: "") +
                (c.variabilitySeconds?.let { " · observed variability ${fmt(it)} s" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(verdictLabel(c.verdict), fontWeight = FontWeight.SemiBold)
        c.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        (c.baseline.invalidReasons + c.treatment.invalidReasons).distinct().takeIf { it.isNotEmpty() }?.let {
            Text("Invalid attempts: ${it.joinToString()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Band-by-band before/after plot: every usable attempt as a dot, session medians as ticks. */
@Composable
private fun ComparisonPlot(cells: List<ComparisonCell>) {
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val values = cells.flatMap { c -> c.baseline.values.map { it.t20Seconds } + c.treatment.values.map { it.t20Seconds } }
    if (values.isEmpty()) { Text("No usable attempts to plot.", style = MaterialTheme.typography.bodySmall); return }
    val hi = (values.max() * 1.1).coerceAtLeast(0.1)
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val left = 40.dp.toPx(); val bottom = size.height - 18.dp.toPx(); val top = 6.dp.toPx()
        val colW = (size.width - left) / cells.size
        fun y(v: Double) = (bottom - (v / hi) * (bottom - top)).toFloat()
        for (frac in listOf(0.0, 0.5, 1.0)) {
            val v = hi * frac
            drawLine(axis.copy(alpha = 0.25f), Offset(left, y(v)), Offset(size.width, y(v)))
            drawText(measurer, "${fmt(v)} s", Offset(0f, y(v) - 7.sp.toPx()), TextStyle(fontSize = 10.sp, color = axis))
        }
        cells.forEachIndexed { i, c ->
            val cx = left + colW * (i + 0.5f)
            drawText(measurer, "${c.bandHz}", Offset(cx - 12.dp.toPx(), bottom + 2.dp.toPx()), TextStyle(fontSize = 10.sp, color = axis))
            listOf(Triple(c.baseline, BaselineColor, -colW * 0.18f), Triple(c.treatment, TreatmentColor, colW * 0.18f)).forEach { (s, color, dx) ->
                s.values.forEach { v -> drawCircle(color, 3.5.dp.toPx(), Offset(cx + dx, y(v.t20Seconds)), alpha = if (v.outlier) 0.4f else 0.9f) }
                s.medianSeconds?.let { m -> drawLine(color, Offset(cx + dx - 8.dp.toPx(), y(m)), Offset(cx + dx + 8.dp.toPx(), y(m)), strokeWidth = 2.dp.toPx()) }
            }
        }
    }
}
