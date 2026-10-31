package se.klang.app.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.Screen
import se.klang.app.ui.SectionCard

@Composable
fun HomeScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val rooms = vm.rooms()
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text(
                "Compare reverberation before and after a change to a room, e.g. adding a panel, curtains or a rug. " +
                    "Results are decay estimates at your chosen positions, from handclaps on this phone's microphone: " +
                    "useful for before/after comparison, not a calibrated or certified measurement.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedButton(onClick = { vm.navigate(Screen.Capability) }, modifier = Modifier.fillMaxWidth()) {
                Text("Microphone check (do this first)")
            }
        }
        item {
            SectionCard("New room") {
                OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Kitchen / dining") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (floor, windows, cabinetry…)") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = name.isNotBlank(), onClick = { vm.createRoom(name.trim(), notes.trim()); name = ""; notes = "" }) {
                    Text("Create room")
                }
            }
        }
        items(rooms, key = { it.id }) { room ->
            Card(Modifier.fillMaxWidth().clickable { vm.navigate(Screen.RoomDetail(room.id)) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(room.name, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${room.positions.size} positions", style = MaterialTheme.typography.bodySmall)
                        Text("${vm.sessions(room.id).size} sessions", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
