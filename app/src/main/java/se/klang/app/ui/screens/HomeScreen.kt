package se.klang.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.Screen

@Composable
fun HomeScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val rooms = vm.rooms()

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Compare reverberation before and after a change to a room, e.g. adding a panel, curtains or a rug. " +
                    "Results are decay estimates at your chosen positions, from handclaps on this phone's microphone: " +
                    "useful for before/after comparison, not a calibrated or certified measurement.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (rooms.isEmpty()) item {
            // Before the first room, point to the microphone check. Afterwards it lives in the top-bar menu.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.navigate(Screen.Capability) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Microphone check (do this first)")
                }
                Text("No rooms yet. Tap New room to start.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(rooms, key = { it.id }) { room ->
            Card(Modifier.fillMaxWidth().clickable { vm.navigate(Screen.RoomDetail(room.id)) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(room.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${room.positions.size} positions · ${vm.sessions(room.id).size} sessions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}
