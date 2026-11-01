package se.klang.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.klang.app.ui.theme.InvalidColor
import se.klang.app.ui.theme.ValidColor
import se.klang.sessions.AttemptReportPolicy
import se.klang.sessions.RecordingTimeline
import se.klang.sessions.SoundEvent
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** dBFS text with a real minus sign, rounded to whole dB ("−24"). */
private fun whole(v: Double) = fmt(v, 0).replace('-', '−')

/**
 * The level of a recording over time: the short-time level (RMS per 50 ms, like the background level) as a
 * filled curve, the background as a dashed line, the clap with its sample peak, and any sound louder than the
 * room. The green band is a good clap peak. dBFS is relative to digital full scale, never sound pressure.
 *
 * [description] is what a screen reader says; the picture adds nothing the texts around it do not say.
 * [backgroundWarn] draws the background in the warning colour (the room was too noisy).
 */
@Composable
fun RecordingGraph(timeline: RecordingTimeline, description: String, modifier: Modifier = Modifier, backgroundWarn: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val ribbonColor = MaterialTheme.colorScheme.surfaceVariant
    val clapRibbon = MaterialTheme.colorScheme.primaryContainer
    val onClapRibbon = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(modifier.fillMaxWidth().height(210.dp).clearAndSetSemantics { contentDescription = description }) {
        val left = 34.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val ribbonH = 15.dp.toPx()
        val top = 21.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val dur = max(1.0, timeline.durationSeconds)
        fun x(t: Double) = (left + t / dur * (right - left)).toFloat()
        fun y(db: Double) = (top + (-db).coerceIn(0.0, 80.0) / 80.0 * (bottom - top)).toFloat()
        val small = TextStyle(fontSize = 10.sp, color = axis)
        val n = timeline.levelsDbfs.size
        val step = timeline.stepSeconds

        fun text(s: String, style: TextStyle, anchorX: Float, topY: Float, align: Int) {
            // align: -1 left of anchor, 0 centred on it, 1 starts at it
            val w = measurer.measure(s, style).size.width
            val px = when (align) { -1 -> anchorX - w; 0 -> anchorX - w / 2f; else -> anchorX }
            drawText(measurer, s, Offset(px.coerceIn(0f, size.width - w), topY), style)
        }

        // The quiet part before the clap, and the band of a good clap peak.
        timeline.onsetSeconds?.let { drawRect(line.copy(alpha = 0.06f), Offset(left, top), Size(x(it) - left, bottom - top)) }
        drawRect(
            ValidColor.copy(alpha = 0.14f),
            Offset(left, y(AttemptReportPolicy.GOOD_CLAP_MAX_DBFS)),
            Size(right - left, y(AttemptReportPolicy.GOOD_CLAP_MIN_DBFS) - y(AttemptReportPolicy.GOOD_CLAP_MAX_DBFS)),
        )
        text("good clap level", TextStyle(fontSize = 9.sp, color = ValidColor), right - 2.dp.toPx(), y(AttemptReportPolicy.GOOD_CLAP_MAX_DBFS) + 1.dp.toPx(), -1)

        // Grid, scale and full scale.
        val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
        for (db in listOf(-20.0, -40.0, -60.0)) {
            drawLine(axis.copy(alpha = 0.25f), Offset(left, y(db)), Offset(right, y(db)), pathEffect = dash)
        }
        drawLine(InvalidColor.copy(alpha = 0.5f), Offset(left, y(0.0)), Offset(right, y(0.0)))
        drawLine(axis, Offset(left, bottom), Offset(right, bottom))
        for (db in listOf(0.0, -20.0, -40.0, -60.0, -80.0)) text(whole(db), small, left - 4.dp.toPx(), y(db) - 6.dp.toPx(), -1)

        // The level.
        if (n > 0) {
            fun px(i: Int) = x((i + 0.5) * step)
            val area = Path().apply {
                moveTo(px(0), bottom)
                for (i in 0 until n) lineTo(px(i), y(timeline.levelsDbfs[i].toDouble()))
                lineTo(px(n - 1), bottom)
                close()
            }
            drawPath(area, line.copy(alpha = 0.22f))
            val curve = Path().apply {
                for (i in 0 until n) { val py = y(timeline.levelsDbfs[i].toDouble()); if (i == 0) moveTo(px(0), py) else lineTo(px(i), py) }
            }
            drawPath(curve, line, style = Stroke(1.6.dp.toPx()))
        }

        // The background.
        (timeline.quietDbfs ?: timeline.backgroundDbfs)?.let { bg ->
            val c = if (backgroundWarn) InvalidColor else axis
            drawLine(c, Offset(left, y(bg)), Offset(right, y(bg)), strokeWidth = 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            text("background ${whole(bg)}${if (backgroundWarn) ": too high" else ""}", TextStyle(fontSize = 10.sp, color = c, fontWeight = if (backgroundWarn) FontWeight.Bold else FontWeight.Normal), right - 2.dp.toPx(), y(bg) - 14.dp.toPx(), -1)
        }

        // The clap: its marker, the loudest window, and the sample peak above it.
        timeline.onsetSeconds?.let { on ->
            drawLine(line.copy(alpha = 0.5f), Offset(x(on), top - 4.dp.toPx()), Offset(x(on), bottom), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 6f)))
            val from = (on / step).toInt().coerceIn(0, max(0, n - 1))
            val w = (from..min(n - 1, from + 6)).maxByOrNull { timeline.levelsDbfs[it] }
            if (w != null) {
                val cx = x((w + 0.5) * step)
                val cy = y(timeline.levelsDbfs[w].toDouble())
                val peakY = y(timeline.peakDbfs)
                if (peakY < cy) drawLine(line, Offset(cx, peakY), Offset(cx, cy), strokeWidth = 1.2.dp.toPx())
                drawLine(line, Offset(cx - 5.dp.toPx(), peakY), Offset(cx + 5.dp.toPx(), peakY), strokeWidth = 2.dp.toPx())
                drawCircle(surface, 5.dp.toPx(), Offset(cx, cy))
                drawCircle(line, 3.5.dp.toPx(), Offset(cx, cy))
                text("clap ${whole(timeline.peakDbfs)}", TextStyle(fontSize = 10.sp, color = line, fontWeight = FontWeight.Bold), cx + 8.dp.toPx(), peakY - 6.dp.toPx(), 1)
            }
        }

        // Sounds louder than the room.
        for (e in timeline.events) {
            val cx = x(e.timeSeconds + step / 2)
            val cy = y(e.levelDbfs)
            when (e.kind) {
                SoundEvent.Kind.BEFORE_CLAP -> {
                    drawCircle(surface, 6.dp.toPx(), Offset(cx, cy))
                    drawCircle(InvalidColor, 6.dp.toPx(), Offset(cx, cy), style = Stroke(2.5.dp.toPx()))
                    text("other sound ${whole(e.levelDbfs)}", TextStyle(fontSize = 10.sp, color = InvalidColor, fontWeight = FontWeight.Bold), cx, cy - 22.dp.toPx(), 0)
                }
                SoundEvent.Kind.AFTER_DECAY -> {
                    drawCircle(surface, 5.dp.toPx(), Offset(cx, cy))
                    drawCircle(axis, 5.dp.toPx(), Offset(cx, cy), style = Stroke(2.dp.toPx()))
                    text("later sound ${whole(e.levelDbfs)}: no effect", small, cx, cy - 20.dp.toPx(), 0)
                }
            }
        }

        // The phases of the recording along the top.
        val onset = timeline.onsetSeconds
        fun segment(from: Float, to: Float, label: String, fill: Color, on: Color, bold: Boolean) {
            if (to - from < 2f) return
            drawRoundRect(fill, Offset(from, 0f), Size(to - from, ribbonH), CornerRadius(3.dp.toPx()))
            val st = TextStyle(fontSize = 9.sp, color = on, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
            val w = measurer.measure(label, st).size.width
            if (w + 4.dp.toPx() <= to - from) drawText(measurer, label, Offset((from + to) / 2 - w / 2f, 1.dp.toPx()), st)
        }
        if (onset != null) {
            val clapEnd = min(dur, onset + 0.5)
            segment(left, x(onset), "Quiet", ribbonColor, axis, false)
            segment(x(onset), x(clapEnd), "Clap", clapRibbon, onClapRibbon, true)
            segment(x(clapEnd), right, "Decay", ribbonColor, axis, false)
        } else segment(left, right, "Recording", ribbonColor, axis, false)

        // Time.
        val seconds = floor(dur).toInt()
        for (t in 0..seconds) {
            drawLine(axis, Offset(x(t.toDouble()), bottom), Offset(x(t.toDouble()), bottom + 4.dp.toPx()))
            val label = if (t == 0) "0 s" else if (t == seconds) "$t s" else "$t"
            text(label, small, x(t.toDouble()), bottom + 6.dp.toPx(), if (t == 0) 1 else if (t == seconds) -1 else 0)
        }
    }
}

/**
 * A small version of [RecordingGraph] for list rows: the level, the background, the clap and sounds louder than
 * the room. Decorative: the row's text carries the meaning. [hot] marks a clap that reached the top of the scale.
 */
@Composable
fun RecordingSparkline(timeline: RecordingTimeline, modifier: Modifier = Modifier, hot: Boolean = false) {
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(32.dp).clearAndSetSemantics { }) {
        val dur = max(1.0, timeline.durationSeconds)
        val h = size.height
        fun x(t: Double) = (t / dur * size.width).toFloat()
        fun y(db: Double) = (1.dp.toPx() + (-db).coerceIn(0.0, 80.0) / 80.0 * (h - 2.dp.toPx())).toFloat()
        val n = timeline.levelsDbfs.size
        if (n == 0) return@Canvas
        for (e in timeline.events) {
            if (e.kind == SoundEvent.Kind.BEFORE_CLAP) drawRect(InvalidColor.copy(alpha = 0.28f), Offset(x(e.timeSeconds) - 1.5.dp.toPx(), 0f), Size(6.dp.toPx(), h))
        }
        if (hot) drawRect(Color(0xFFB25E00).copy(alpha = 0.6f), Offset.Zero, Size(size.width, 2.5.dp.toPx()))
        (timeline.quietDbfs ?: timeline.backgroundDbfs)?.let { bg ->
            drawLine(axis, Offset(0f, y(bg)), Offset(size.width, y(bg)), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
        }
        timeline.onsetSeconds?.let { drawLine(line.copy(alpha = 0.5f), Offset(x(it), 0f), Offset(x(it), h), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f))) }
        fun px(i: Int) = x((i + 0.5) * timeline.stepSeconds)
        val area = Path().apply {
            moveTo(px(0), h)
            for (i in 0 until n) lineTo(px(i), y(timeline.levelsDbfs[i].toDouble()))
            lineTo(px(n - 1), h)
            close()
        }
        drawPath(area, line.copy(alpha = 0.2f))
        val curve = Path().apply { for (i in 0 until n) { val py = y(timeline.levelsDbfs[i].toDouble()); if (i == 0) moveTo(px(0), py) else lineTo(px(i), py) } }
        drawPath(curve, line, style = Stroke(1.5.dp.toPx()))
    }
}

/**
 * How far the clap stood above the noise in a band, against the margin that is needed (the tick). Drawn in the
 * warning colour when it falls short.
 */
@Composable
fun MarginBar(marginDb: Double?, neededDb: Double, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val ok = MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.onSurface
    val scale = 60.0
    Canvas(modifier.width(96.dp).height(14.dp)) {
        val w = size.width
        val cy = size.height / 2
        val bar = 8.dp.toPx()
        drawRoundRect(track, Offset(0f, cy - bar / 2), Size(w, bar), CornerRadius(bar / 2))
        if (marginDb != null) {
            val fill = (marginDb.coerceIn(0.0, scale) / scale * w).toFloat()
            drawRoundRect(if (marginDb >= neededDb) ok else InvalidColor, Offset(0f, cy - bar / 2), Size(fill, bar), CornerRadius(bar / 2))
        }
        val tx = (neededDb / scale * w).toFloat()
        drawLine(tick, Offset(tx, cy - 7.dp.toPx()), Offset(tx, cy + 7.dp.toPx()), strokeWidth = 2.dp.toPx())
    }
}
