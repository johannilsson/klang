package se.klang.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.Screen
import se.klang.app.Sheet
import se.klang.sessions.MeasurementPosition
import se.klang.sessions.SessionKind

val PROTOCOL = listOf(
    "Add three positions around the dining area at about seated ear height. Each position has its own fixed clap spot " +
        "(start about 1.5–2 m from the phone, then use Practice clap level to adjust); note the distance and direction.",
    "Support the phone (stand or stack of books). Keep its position, orientation, case and microphone the same every time.",
    "Keep your own position, clap direction and distance the same at each clap spot.",
    "Keep doors, windows, furnishings and number of people the same. Turn off the dishwasher and extractor fan where practical.",
    "In each session, record a few seconds of background at each position.",
    "Then record five separate claps per position: one crisp clap each, about as firm as applause, not your hardest (aim for the green range on the level meter). Let each decay finish. Retry when told, never progressively louder.",
    "Repeat the whole session without changing the room (Repeat baseline), to see how much results vary on their own.",
    "Then add one change (e.g. one panel) and repeat the same protocol (Treatment).",
)

const val PROTOCOL_FOOTNOTE =
    "Each position is reported separately; no single position represents the whole room. " +
        "Repetition counts are app defaults, not a standards requirement."

@Composable
fun RoomScreen(vm: AppViewModel, roomId: String, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val room = vm.room(roomId) ?: return
    val sessions = vm.sessions(roomId)

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (room.notes.isNotBlank()) item {
            Text(room.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (room.positions.isEmpty() || sessions.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Set up this room once", style = MaterialTheme.typography.titleMedium)
                    Text("1. Add the positions where the phone will stand, each with its clap spot.", style = MaterialTheme.typography.bodyMedium)
                    Text("2. Then start a baseline session and record at every position.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.sheet = Sheet.Protocol }) { Text("Read the full protocol") }
                }
            }
        }

        item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeader("Positions · ${room.positions.size}") {
                TextButton(onClick = { vm.sheet = Sheet.AddPosition(roomId) }) { Text("+ Add position") }
            }
            if (room.positions.isEmpty()) {
                Text("No positions yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Card(Modifier.fillMaxWidth()) {
                    room.positions.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider()
                        PositionRow(i + 1, p)
                    }
                }
            }
        } }

        item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeader("Sessions · ${sessions.size}")
            if (sessions.isEmpty()) {
                Text(
                    if (room.positions.isEmpty()) "Add a position first, then start a baseline." else "No sessions yet. Start with a baseline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } }
        items(sessions, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth().clickable { vm.navigate(Screen.SessionDetail(s.id)) }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        KindChip(s.kind)
                    }
                    Text(
                        (if (s.intervention.isNotBlank()) "${s.intervention} · " else "") + "${vm.attempts(s.id).size} recordings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Room for the floating action button.
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun PositionRow(number: Int, p: MeasurementPosition) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        NumberBadge(number.toString())
        Column {
            Text(p.name, style = MaterialTheme.typography.bodyLarge)
            val detail = listOfNotNull(p.placementNotes.takeIf { it.isNotBlank() }, p.clapSpot.takeIf { it.isNotBlank() }?.let { "Clap: $it" })
            Text(
                detail.joinToString(" · ").ifEmpty { "No placement notes" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun NumberBadge(text: String, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Box(Modifier.size(32.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
fun KindChip(kind: SessionKind) {
    val treatment = kind == SessionKind.TREATMENT
    val bg = if (treatment) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (treatment) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Text(
        kindLabel(kind),
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

fun kindLabel(k: SessionKind) = when (k) {
    SessionKind.BASELINE -> "Baseline"
    SessionKind.REPEAT_BASELINE -> "Repeat baseline"
    SessionKind.TREATMENT -> "Treatment"
}
