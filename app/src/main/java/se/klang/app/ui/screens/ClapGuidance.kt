package se.klang.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    "Stand at the position's clap spot. As a starting point, about 1.5–2 m from the phone with your hands at the phone's height. " +
        "That is a suggestion, not a validated rule: use \"Practice clap level\" to find what works in your room.",
    "Clap to the side, not straight at the phone. Flat hands, one crisp clap, about as firm as applause — not your hardest.",
    "Louder is not better. The clap only needs to peak in the green range (${fmt(TOO_QUIET_DBFS, 0)} to ${fmt(TOO_LOUD_DBFS, 0)} dBFS) " +
        "on the level meter. A harder clap risks the microphone compressing it.",
    "Too loud: clap softer or step back. Each doubling of the distance makes the clap about 6 dB quieter (a little less in a " +
        "reverberant room). Too quiet: step closer.",
    "Use exactly the same spot, direction, hand position and strength in every session. Note the distance and direction in the position's clap spot.",
    "Stay still until the phone buzzes.",
)

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
