package se.klang.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.klang.app.ui.SectionCard
import se.klang.app.ui.fmt
import se.klang.app.ui.theme.InvalidColor
import se.klang.app.ui.theme.QuestionableColor
import se.klang.app.ui.theme.ValidColor

/**
 * Clap peak guidance, dBFS (relative to digital full scale, not SPL). Upper limit: the analysis warns
 * at -1 dBFS. Lower limit: from real captures (Pixel 9 Pro) valid claps peaked at -1 to -4 dBFS with
 * 35 dB needed over the background; much quieter claps risk failing the margin in the outer bands.
 */
const val TOO_LOUD_DBFS = -2.0
const val TOO_QUIET_DBFS = -12.0

data class ClapFeedback(val color: Color, val text: String)

fun clapFeedback(peakDbfs: Double): ClapFeedback = when {
    peakDbfs >= TOO_LOUD_DBFS -> ClapFeedback(InvalidColor, "Too loud. Clap softer or step back.")
    peakDbfs < TOO_QUIET_DBFS -> ClapFeedback(QuestionableColor, "Quiet. Step closer or clap a little firmer.")
    else -> ClapFeedback(ValidColor, "Good level.")
}

/** Concrete clap instructions. The 1.5–2 m start distance is a suggestion, not a validated rule. */
val HOW_TO_CLAP = listOf(
    "Stand at your marked clap spot. As a starting point, about 1.5–2 m from the phone with your hands at the phone's height. " +
        "That is a suggestion, not a validated rule: use \"Practice clap level\" to find what works in your room.",
    "Clap to the side, not straight at the phone. Flat hands, one crisp clap, about as firm as applause — not your hardest.",
    "Louder is not better. The clap only needs to peak in the green range (${fmt(TOO_QUIET_DBFS, 0)} to ${fmt(TOO_LOUD_DBFS, 0)} dBFS) " +
        "on the level meter. A harder clap risks the microphone compressing it.",
    "Too loud: clap softer or step back. Each doubling of the distance makes the clap about 6 dB quieter (a little less in a " +
        "reverberant room). Too quiet: step closer.",
    "Use exactly the same spot, direction, hand position and strength in every session. Write the distance in the position's placement notes.",
    "Stay still until the phone buzzes.",
)

@Composable
fun HowToClapCard(onPractice: (() -> Unit)?) {
    var open by remember { mutableStateOf(true) }
    SectionCard("How to clap") {
        if (open) {
            HOW_TO_CLAP.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = onPractice != null, onClick = { onPractice?.invoke() }) { Text("Practice clap level") }
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide tips" else "Show tips") }
        }
        Text("Practice listens for 30 s and saves nothing.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ClapFeedbackText(label: String, peakDbfs: Double) {
    val f = clapFeedback(peakDbfs)
    Text(
        "$label ${fmt(peakDbfs, 1)} dBFS. ${f.text}",
        color = f.color,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
}
