package se.klang.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import se.klang.app.AppViewModel
import se.klang.app.Screen
import se.klang.app.ui.FabScreenPadding

@Composable
fun HomeScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val rooms = vm.rooms()

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = FabScreenPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Compare reverberation before and after a change to a room, e.g. adding a panel, curtains or a rug. " +
                    "Results are decay estimates at your chosen positions, from handclaps on this phone's microphone: " +
                    "useful for before/after comparison, not a calibrated or certified measurement.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rooms.isEmpty()) {
            // Before the first room, point to the microphone check. Afterwards it lives in the top-bar menu.
            item {
                Card(
                    Modifier.fillMaxWidth().clickable { vm.navigate(Screen.Capability) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                        headlineContent = { Text("Microphone check") },
                        supportingContent = { Text("Do this first: see what this phone's microphone supports.") },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            item {
                Text(
                    "No rooms yet. Tap New room to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item { SectionHeader("Rooms") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    rooms.forEachIndexed { i, room ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                        ListItem(
                            headlineContent = { Text(room.name) },
                            supportingContent = { Text("${room.positions.size} positions · ${vm.sessions(room.id).size} sessions") },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { vm.navigate(Screen.RoomDetail(room.id)) },
                        )
                    }
                }
            }
        }
    }
}
