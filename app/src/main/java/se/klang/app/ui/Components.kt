package se.klang.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import se.klang.app.ui.theme.InvalidColor
import se.klang.app.ui.theme.QuestionableColor
import se.klang.app.ui.theme.ValidColor
import se.klang.dsp.QualityState
import se.klang.dsp.ReasonCode
import se.klang.sessions.Curve
import se.klang.sessions.Fit
import java.util.Locale

/**
 * Screen insets for scrolling content: 16 dp margins (Material compact width), 8 dp under the top bar,
 * 24 dp at the end. Screens with a floating action button use [FabScreenPadding] so the last item clears it.
 */
val ScreenPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp)
val FabScreenPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp)

fun fmt(v: Double?, digits: Int = 2): String = v?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: "—"

fun stateColor(s: QualityState): Color = when (s) {
    QualityState.VALID -> ValidColor
    QualityState.QUESTIONABLE -> QuestionableColor
    QualityState.INVALID -> InvalidColor
}

fun stateLabel(s: QualityState): String = when (s) {
    QualityState.VALID -> "Valid"
    QualityState.QUESTIONABLE -> "Questionable"
    QualityState.INVALID -> "Invalid"
}

/** Practical advice per reason. Never suggests clapping harder. */
fun advice(code: ReasonCode): String? = when (code) {
    ReasonCode.CLIPPING, ReasonCode.FULL_SCALE_SAMPLE, ReasonCode.NEAR_FULL_SCALE, ReasonCode.FLAT_TOP ->
        "Too loud for the microphone. Clap less hard or stand a little further from the phone."
    ReasonCode.INSUFFICIENT_SNR_MARGIN, ReasonCode.INSUFFICIENT_DECAY_RANGE ->
        "Background too loud for this band. Turn off fans, the dishwasher or the extractor; do not clap harder."
    ReasonCode.ADDITIONAL_IMPULSE, ReasonCode.BACKGROUND_TRANSIENT ->
        "Another sound was picked up. Clap once, then stay still and quiet until recording ends."
    ReasonCode.INSUFFICIENT_PRE_ROLL -> "Wait for the \"Clap now\" prompt before clapping."
    ReasonCode.INSUFFICIENT_TAIL -> "Clap promptly when asked so the decay fits in the recording."
    ReasonCode.CAPTURE_DISCONTINUITY, ReasonCode.DROPOUT ->
        "The recording was disturbed (audio route change, interruption or dropped samples). Try again without touching the phone."
    ReasonCode.NO_IMPULSE_FOUND -> "No clap detected. Clap once, clearly, after the prompt."
    else -> null
}

@Composable
fun StateChip(state: QualityState, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(stateColor(state).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(stateLabel(state), color = stateColor(state), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // 16 dp inside, 8 dp between lines; spacing between cards comes from the list (12 dp).
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

/** Returns a function that runs [onGranted] after making sure the microphone permission is granted. */
@Composable
fun rememberWithMicPermission(onDenied: () -> Unit, onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted() else onDenied()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/**
 * Energy-decay curve (bold), short-time band level (light), background level (dashed) and the
 * fitted T20 line over its fit interval. Y axis: dB relative to band peak, 0 to -70.
 */
@Composable
fun DecayPlot(curve: Curve, t20: Fit?, modifier: Modifier = Modifier, label: String? = null) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val edcColor = MaterialTheme.colorScheme.primary
    val envColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val fitColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.fillMaxWidth().height(200.dp)) {
        val left = 36.dp.toPx(); val bottom = size.height - 18.dp.toPx(); val top = 4.dp.toPx(); val right = size.width - 4.dp.toPx()
        val maxT = (curve.envelopeDb.size * curve.stepSeconds).coerceAtLeast(0.1)
        val minDb = -70.0
        fun x(t: Double) = (left + (t / maxT) * (right - left)).toFloat()
        fun y(db: Double) = (top + (db.coerceIn(minDb, 0.0) / minDb) * (bottom - top)).toFloat()
        // Axes and grid
        for (db in listOf(0.0, -20.0, -40.0, -60.0)) {
            drawLine(axisColor.copy(alpha = 0.25f), Offset(left, y(db)), Offset(right, y(db)))
            drawText(measurer, "${db.toInt()}", Offset(0f, y(db) - 7.sp.toPx()), TextStyle(fontSize = 10.sp, color = axisColor))
        }
        drawText(measurer, "${fmt(maxT, 1)} s", Offset(right - 32.dp.toPx(), bottom + 2.dp.toPx()), TextStyle(fontSize = 10.sp, color = axisColor))
        drawText(measurer, "0", Offset(left, bottom + 2.dp.toPx()), TextStyle(fontSize = 10.sp, color = axisColor))
        // Fit interval shading
        t20?.let { drawRect(fitColor.copy(alpha = 0.10f), Offset(x(it.startSeconds), top), androidx.compose.ui.geometry.Size(x(it.endSeconds) - x(it.startSeconds), bottom - top)) }
        // Background level
        drawLine(axisColor, Offset(left, y(curve.backgroundDb)), Offset(right, y(curve.backgroundDb)), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        fun series(values: List<Float>): Path = Path().apply {
            values.forEachIndexed { i, v ->
                val px = x(i * curve.stepSeconds); val py = y(v.toDouble())
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        drawPath(series(curve.envelopeDb), envColor, style = Stroke(1.dp.toPx()))
        drawPath(series(curve.edcDb), edcColor, style = Stroke(2.dp.toPx()))
        t20?.let {
            val t0 = 0.0; val t1 = -60.0 / it.slopeDbPerSecond * 1.05 + 0.0
            drawLine(
                fitColor,
                Offset(x(t0), y(it.interceptDb + it.slopeDbPerSecond * t0)),
                Offset(x(t1.coerceAtMost(maxT)), y(it.interceptDb + it.slopeDbPerSecond * t1.coerceAtMost(maxT))),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
            )
        }
        label?.let { drawText(measurer, it, Offset(left + 4.dp.toPx(), top), TextStyle(fontSize = 11.sp, color = InvalidColor)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("— EDC", color = edcColor, style = MaterialTheme.typography.labelSmall)
        Text("— band level", color = envColor, style = MaterialTheme.typography.labelSmall)
        Text("- - background", color = axisColor, style = MaterialTheme.typography.labelSmall)
        if (t20 != null) Text("▒ T20 fit −5…−25 dB", color = fitColor, style = MaterialTheme.typography.labelSmall)
    }
}

/** Example data only, clearly marked: used for the design preview, never shown as a measurement. */
@Preview(showBackground = true)
@Composable
private fun DecayPlotExamplePreview() {
    val step = 0.002
    val edc = List(300) { (-60.0 / 0.5 * it * step).toFloat() }
    val env = List(600) { i -> maxOf(-60.0 / 0.5 * i * step + (if (i % 7 == 0) -3.0 else 0.0), -48.0).toFloat() }
    val fit = Fit(0.5, -120.0, 0.0, -5.0, -25.0, 0.042, 0.208, 0.999, 1.0, 0.1)
    Column(Modifier.padding(8.dp)) {
        DecayPlot(Curve(step, edc, env, -48.0), fit, label = "EXAMPLE DATA — not a measurement")
    }
}
