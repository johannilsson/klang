package se.klang.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.klang.app.AppViewModel
import se.klang.app.Screen
import se.klang.app.ui.SectionCard
import se.klang.sessions.RoomConditions
import se.klang.sessions.SessionKind

val PROTOCOL = listOf(
    "Define three positions around the dining area at about seated ear height.",
    "Support the phone (stand or stack of books). Keep its position, orientation, case and microphone the same every time.",
    "Mark a separate, fixed clap spot (start about 1.5–2 m from the phone, then use Practice clap level in a session to adjust). Keep your own position, clap direction and distance the same, and note the distance.",
    "Keep doors, windows, furnishings and number of people the same. Turn off the dishwasher and extractor fan where practical.",
    "Record a few seconds of background at each position.",
    "Record five separate claps per position: one crisp clap each, about as firm as applause, not your hardest (aim for the green range on the level meter). Let each decay finish. Retry when told, never progressively louder.",
    "Repeat the whole session without changing the room, to see how much results vary on their own.",
    "Then add one change (e.g. one panel) and repeat the same protocol.",
)

@Composable
fun RoomScreen(vm: AppViewModel, roomId: String, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val room = vm.room(roomId) ?: return
    val sessions = vm.sessions(roomId)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var posName by remember { mutableStateOf("") }
    var posNotes by remember { mutableStateOf("") }

    var sName by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(SessionKind.BASELINE) }
    var intervention by remember { mutableStateOf("") }
    var doors by remember { mutableStateOf("") }
    var furnishings by remember { mutableStateOf("") }
    var occupancy by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var appliancesOff by remember { mutableStateOf(true) }

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        item {
            SectionCard("Measurement protocol") {
                PROTOCOL.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Each position is reported separately; no single position represents the whole room. " +
                        "Repetition counts are app defaults, not a standards requirement.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            SectionCard("Positions") {
                room.positions.forEach { p ->
                    Column {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        if (p.placementNotes.isNotBlank()) Text(p.placementNotes, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(posName, { posName = it }, label = { Text("Position name, e.g. Seat by window") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(posNotes, { posNotes = it }, label = { Text("Placement: height, support, orientation, distance to source") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = posName.isNotBlank(), onClick = { vm.addPosition(roomId, posName.trim(), posNotes.trim()); posName = ""; posNotes = "" }) {
                    Text("Add position")
                }
            }
        }
        item {
            SectionCard("Sessions") {
                if (sessions.isEmpty()) Text("No sessions yet. Start with a baseline.")
            }
        }
        items(sessions, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { vm.navigate(Screen.SessionDetail(s.id)) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall)
                    Text(kindLabel(s.kind) + if (s.intervention.isNotBlank()) " · ${s.intervention}" else "", style = MaterialTheme.typography.bodySmall)
                    Text("${vm.attempts(s.id).size} recordings", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            SectionCard("New session") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SessionKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(kindLabel(k)) }) }
                }
                OutlinedTextField(sName, { sName = it }, label = { Text("Session name") }, modifier = Modifier.fillMaxWidth())
                if (kind == SessionKind.TREATMENT) {
                    OutlinedTextField(intervention, { intervention = it }, label = { Text("Intervention, e.g. one wall panel added") }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(doors, { doors = it }, label = { Text("Doors and windows") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(furnishings, { furnishings = it }, label = { Text("Furnishings") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(occupancy, { occupancy = it }, label = { Text("People in the room") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(source, { source = it }, label = { Text("Clap source position") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(appliancesOff, { appliancesOff = it }); Text("Dishwasher, extractor fan etc. off")
                }
                Button(
                    enabled = sName.isNotBlank() && (kind != SessionKind.TREATMENT || intervention.isNotBlank()),
                    onClick = {
                        vm.createSession(
                            roomId, sName.trim(), kind, if (kind == SessionKind.TREATMENT) intervention.trim() else "",
                            RoomConditions(doors.trim(), furnishings.trim(), occupancy.trim(), appliancesOff, source.trim()),
                        )
                        sName = ""; intervention = ""
                    },
                ) { Text("Create session") }
            }
        }
        item {
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.navigate(Screen.Compare(roomId)) }) { Text("Compare sessions") }
                OutlinedButton(onClick = {
                    scope.launch { context.startActivity(Intent.createChooser(vm.exportRoom(roomId, emptyList()), "Export room")) }
                }) { Text("Export all") }
            }
        }
    }
}

fun kindLabel(k: SessionKind) = when (k) {
    SessionKind.BASELINE -> "Baseline"
    SessionKind.REPEAT_BASELINE -> "Repeat baseline"
    SessionKind.TREATMENT -> "Treatment"
}
