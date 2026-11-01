package se.klang.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.klang.app.AppViewModel
import se.klang.app.Screen
import se.klang.app.Sheet
import se.klang.app.ui.rememberWithMicPermission
import se.klang.sessions.RoomConditions
import se.klang.sessions.Session
import se.klang.sessions.SessionKind

/** Renders whichever sheet [AppViewModel.sheet] names. Lives at the app root so menus and buttons can open it. */
@Composable
fun SheetHost(vm: AppViewModel) {
    val close = { vm.sheet = null }
    val practice = rememberWithMicPermission(onDenied = { vm.permissionDenied() }) { vm.startPractice() }
    when (val s = vm.sheet) {
        null -> Unit
        Sheet.NewRoom -> NewRoomSheet(vm, close)
        is Sheet.AddPosition -> AddPositionSheet(vm, s.roomId, close)
        is Sheet.NewSession -> NewSessionSheet(vm, s.roomId, close)
        Sheet.Protocol -> TextSheet("Measurement protocol", PROTOCOL, PROTOCOL_FOOTNOTE, close)
        Sheet.HowToClap -> HowToClapSheet(
            onPractice = if (vm.isCapturing) null else ({ close(); practice() }),
            onDismiss = close,
        )
        is Sheet.Conditions -> vm.session(s.sessionId)?.let { session ->
            ConditionsSheet(session, onEdit = { vm.sheet = Sheet.EditSession(session.id) }, onDismiss = close)
        } ?: close()
        is Sheet.EditRoom -> EditRoomSheet(vm, s.roomId, close)
        is Sheet.EditPosition -> EditPositionSheet(vm, s.roomId, s.positionId, close)
        is Sheet.EditSession -> EditSessionSheet(vm, s.sessionId, close)
    }
    vm.confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.confirmDelete = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = {
                TextButton(
                    onClick = { vm.confirmDelete = null; c.onConfirm() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { vm.confirmDelete = null }) { Text("Keep") } },
        )
    }
}

/** The ⋮ menu in the top bar: secondary actions per screen, so the screens themselves stay short. */
@Composable
fun TopBarMenu(vm: AppViewModel, screen: Screen) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun export(roomId: String) {
        scope.launch { context.startActivity(Intent.createChooser(vm.exportRoom(roomId, emptyList()), "Export room")) }
    }
    val items: List<Pair<String, () -> Unit>> = when (screen) {
        Screen.Home -> listOf("Microphone check" to { vm.navigate(Screen.Capability) })
        is Screen.RoomDetail -> listOf(
            "Measurement protocol" to { vm.sheet = Sheet.Protocol },
            "Compare sessions" to { vm.navigate(Screen.Compare(screen.roomId)) },
            "Export all" to { export(screen.roomId) },
            "Microphone check" to { vm.navigate(Screen.Capability) },
            "Edit room" to { vm.sheet = Sheet.EditRoom(screen.roomId) },
            "Delete room" to { vm.askDeleteRoom(screen.roomId) },
        )
        is Screen.SessionDetail -> {
            val roomId = vm.session(screen.sessionId)?.roomId
            listOfNotNull(
                "How to clap" to { vm.sheet = Sheet.HowToClap },
                "Session conditions" to { vm.sheet = Sheet.Conditions(screen.sessionId) },
                roomId?.let { "Add position to room" to { vm.sheet = Sheet.AddPosition(it) } },
                "Measurement protocol" to { vm.sheet = Sheet.Protocol },
                "Edit session" to { vm.sheet = Sheet.EditSession(screen.sessionId) },
                "Delete session" to { vm.askDeleteSession(screen.sessionId) },
            )
        }
        else -> emptyList()
    }
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(enabled = !vm.isCapturing, onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KlangSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Cancel and confirm at the end; an optional destructive action (e.g. Delete) at the start. */
@Composable
private fun SheetActions(
    confirm: String, enabled: Boolean, onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null, deleteLabel: String = "Delete",
    onConfirm: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        if (onDelete != null) {
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(deleteLabel)
            }
            Spacer(Modifier.weight(1f))
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
        Button(enabled = enabled, onClick = onConfirm) { Text(confirm) }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, placeholder: String? = null) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NewRoomSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    KlangSheet(onDismiss) {
        SheetTitle("New room")
        Field(name, { name = it }, "Name", "e.g. Kitchen / dining")
        Field(notes, { notes = it }, "Notes (optional)", "Floor, windows, cabinetry…")
        SheetActions("Create", name.isNotBlank(), onDismiss) {
            val room = vm.createRoom(name.trim(), notes.trim())
            vm.navigate(Screen.RoomDetail(room.id))
        }
    }
}

@Composable
private fun AddPositionSheet(vm: AppViewModel, roomId: String, onDismiss: () -> Unit) {
    val room = vm.room(roomId) ?: return onDismiss()
    var name by remember { mutableStateOf("") }
    var placement by remember { mutableStateOf("") }
    var clapSpot by remember { mutableStateOf("") }
    KlangSheet(onDismiss) {
        SheetTitle("Add position", "Belongs to ${room.name}. Every session records at the same positions.")
        Field(name, { name = it }, "Name", "e.g. Seat by window")
        Field(placement, { placement = it }, "Phone placement (optional)", "Height, support, orientation")
        Field(clapSpot, { clapSpot = it }, "Clap spot (optional)", "e.g. 1.8 m from phone, toward kitchen")
        SheetActions("Add", name.isNotBlank(), onDismiss) {
            vm.addPosition(roomId, name.trim(), placement.trim(), clapSpot.trim())
            onDismiss()
        }
    }
}

@Composable
private fun EditRoomSheet(vm: AppViewModel, roomId: String, onDismiss: () -> Unit) {
    val room = vm.room(roomId) ?: return onDismiss()
    var name by remember(roomId) { mutableStateOf(room.name) }
    var notes by remember(roomId) { mutableStateOf(room.notes) }
    KlangSheet(onDismiss) {
        SheetTitle("Edit room")
        Field(name, { name = it }, "Name")
        Field(notes, { notes = it }, "Notes (optional)", "Floor, windows, cabinetry…")
        SheetActions("Save", name.isNotBlank(), onDismiss, onDelete = { vm.askDeleteRoom(roomId) }) {
            vm.updateRoom(roomId, name.trim(), notes.trim())
            onDismiss()
        }
    }
}

@Composable
private fun EditPositionSheet(vm: AppViewModel, roomId: String, positionId: String, onDismiss: () -> Unit) {
    val room = vm.room(roomId) ?: return onDismiss()
    val p = room.positions.firstOrNull { it.id == positionId } ?: return onDismiss()
    val recordings = remember(positionId, vm.revision) { vm.recordingsAtPosition(roomId, positionId) }
    var name by remember(positionId) { mutableStateOf(p.name) }
    var placement by remember(positionId) { mutableStateOf(p.placementNotes) }
    var clapSpot by remember(positionId) { mutableStateOf(p.clapSpot) }
    KlangSheet(onDismiss) {
        SheetTitle(
            "Edit position",
            if (recordings == 0) "Belongs to ${room.name}."
            else "$recordings recording(s) were made here. Edits only change the description, not the recordings: " +
                "if the phone or clap spot really moved, add a new position instead so comparisons stay like for like.",
        )
        Field(name, { name = it }, "Name")
        Field(placement, { placement = it }, "Phone placement (optional)", "Height, support, orientation")
        Field(clapSpot, { clapSpot = it }, "Clap spot (optional)", "e.g. 1.8 m from phone, toward kitchen")
        SheetActions("Save", name.isNotBlank(), onDismiss, onDelete = { vm.askDeletePosition(roomId, positionId) }) {
            vm.updatePosition(roomId, positionId, name.trim(), placement.trim(), clapSpot.trim())
            onDismiss()
        }
    }
}

/** Form state shared by the new-session and edit-session sheets. */
private class SessionForm(kind: SessionKind, name: String, intervention: String, c: RoomConditions) {
    var kind by mutableStateOf(kind)
    var name by mutableStateOf(name)
    var intervention by mutableStateOf(intervention)
    var doors by mutableStateOf(c.doorsAndWindows)
    var furnishings by mutableStateOf(c.furnishings)
    var occupancy by mutableStateOf(c.occupancy)
    var appliancesOff by mutableStateOf(c.appliancesOff ?: true)
    val valid get() = name.isNotBlank() && (kind != SessionKind.TREATMENT || intervention.isNotBlank())
    val interventionOrEmpty get() = if (kind == SessionKind.TREATMENT) intervention.trim() else ""

    /** [base] keeps fields the form does not edit (legacy clap source, notes). */
    fun conditions(base: RoomConditions = RoomConditions()) =
        base.copy(doorsAndWindows = doors.trim(), furnishings = furnishings.trim(), occupancy = occupancy.trim(), appliancesOff = appliancesOff)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionFormFields(form: SessionForm, conditionsSummaryPrefix: String, conditionsInitiallyOpen: Boolean, onKind: (SessionKind) -> Unit) {
    var conditionsOpen by remember { mutableStateOf(conditionsInitiallyOpen) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SessionKind.entries.forEachIndexed { i, k ->
            SegmentedButton(
                selected = form.kind == k,
                onClick = { onKind(k) },
                shape = SegmentedButtonDefaults.itemShape(i, SessionKind.entries.size),
                icon = {},
                label = { Text(if (k == SessionKind.REPEAT_BASELINE) "Repeat" else kindLabel(k), maxLines = 1) },
            )
        }
    }
    Text(
        when (form.kind) {
            SessionKind.BASELINE -> "The room as it is now."
            SessionKind.REPEAT_BASELINE -> "Room unchanged, to see how much results vary on their own."
            SessionKind.TREATMENT -> "One change added to the room."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Field(form.name, { form.name = it }, "Name")
    if (form.kind == SessionKind.TREATMENT) {
        Field(form.intervention, { form.intervention = it }, "What changed (required)", "e.g. one wall panel behind the table")
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Room conditions", style = MaterialTheme.typography.titleSmall)
                    val parts = listOfNotNull(
                        form.doors.takeIf { it.isNotBlank() }?.let { "doors/windows: $it" },
                        form.furnishings.takeIf { it.isNotBlank() },
                        form.occupancy.takeIf { it.isNotBlank() }?.let { "people: $it" },
                        if (form.appliancesOff) "appliances off" else "appliances on",
                    )
                    Text(
                        conditionsSummaryPrefix + parts.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { conditionsOpen = !conditionsOpen }) { Text(if (conditionsOpen) "Done" else "Edit") }
            }
            if (conditionsOpen) {
                Field(form.doors, { form.doors = it }, "Doors and windows")
                Field(form.furnishings, { form.furnishings = it }, "Furnishings")
                Field(form.occupancy, { form.occupancy = it }, "People in the room")
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = form.appliancesOff, role = Role.Checkbox, onValueChange = { form.appliancesOff = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(form.appliancesOff, onCheckedChange = null)
                    Text("Dishwasher, extractor fan etc. off", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun NewSessionSheet(vm: AppViewModel, roomId: String, onDismiss: () -> Unit) {
    val room = vm.room(roomId) ?: return onDismiss()
    val last = remember(roomId) { vm.lastConditions(roomId) }
    val form = remember(roomId) {
        val kind = vm.nextSessionKind(roomId)
        SessionForm(kind, vm.defaultSessionName(roomId, kind, kindLabel(kind)), "", last ?: RoomConditions(appliancesOff = true))
    }
    KlangSheet(onDismiss) {
        SheetTitle("New session", "Records at all ${room.positions.size} positions of ${room.name}.")
        // First session: conditions are open so they get described once. Later: collapsed, prefilled from the last session.
        SessionFormFields(form, if (last != null) "Same as last session: " else "", conditionsInitiallyOpen = last == null) { k ->
            form.kind = k; form.name = vm.defaultSessionName(roomId, k, kindLabel(k))
        }
        SheetActions("Start session", form.valid, onDismiss) {
            val s = vm.createSession(roomId, form.name.trim(), form.kind, form.interventionOrEmpty, form.conditions())
            vm.navigate(Screen.SessionDetail(s.id))
        }
    }
}

@Composable
private fun EditSessionSheet(vm: AppViewModel, sessionId: String, onDismiss: () -> Unit) {
    val session = vm.session(sessionId) ?: return onDismiss()
    val form = remember(sessionId) { SessionForm(session.kind, session.name, session.intervention, session.conditions) }
    KlangSheet(onDismiss) {
        SheetTitle(
            "Edit session",
            "Corrects how the session is described. Its recordings and their analyses are not changed.",
        )
        SessionFormFields(form, "", conditionsInitiallyOpen = true) { k -> form.kind = k }
        SheetActions("Save", form.valid, onDismiss, onDelete = { vm.askDeleteSession(sessionId) }) {
            vm.updateSession(sessionId, form.name.trim(), form.kind, form.interventionOrEmpty, form.conditions(session.conditions))
            onDismiss()
        }
    }
}

@Composable
private fun TextSheet(title: String, steps: List<String>, footnote: String?, onDismiss: () -> Unit) {
    KlangSheet(onDismiss) {
        SheetTitle(title)
        steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
        if (footnote != null) Text(footnote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Button(onClick = onDismiss) { Text("Got it") } }
    }
}

@Composable
private fun HowToClapSheet(onPractice: (() -> Unit)?, onDismiss: () -> Unit) {
    KlangSheet(onDismiss) {
        SheetTitle("How to clap")
        HOW_TO_CLAP.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
        Text("Practice listens for 30 s and saves nothing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onDismiss) { Text("Close") }
            Button(enabled = onPractice != null, onClick = { onPractice?.invoke() }) { Text("Practice clap level") }
        }
    }
}

@Composable
private fun ConditionsSheet(session: Session, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val c = session.conditions
    KlangSheet(onDismiss) {
        SheetTitle("Session conditions", kindLabel(session.kind) + if (session.intervention.isNotBlank()) " · ${session.intervention}" else "")
        listOf(
            "Doors/windows" to c.doorsAndWindows,
            "Furnishings" to c.furnishings,
            "People" to c.occupancy,
            "Appliances off" to (c.appliancesOff?.let { if (it) "Yes" else "No" } ?: ""),
            // Sessions from before clap spots moved to positions may carry their own source description.
            "Clap source (session)" to c.sourcePosition,
        ).filter { (k, v) -> v.isNotBlank() || k != "Clap source (session)" }.forEach { (k, v) ->
            ListItem(
                overlineContent = { Text(k) },
                headlineContent = { Text(v.ifBlank { "Not noted" }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onDismiss) { Text("Close") }
            Button(onClick = onEdit) { Text("Edit") }
        }
    }
}
