package se.klang.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import se.klang.app.ui.ConfirmPermanentDelete
import se.klang.app.ui.plural
import se.klang.app.ui.rememberWithMicPermission
import se.klang.sessions.Room
import se.klang.sessions.RoomConditions
import se.klang.sessions.Session
import se.klang.sessions.SessionKind

/** Renders whichever sheet [AppViewModel.sheet] names. Lives at the app root so menus and buttons can open it. */
@Composable
fun SheetHost(vm: AppViewModel) {
    val close = { vm.sheet = null }
    val practice = rememberWithMicPermission(onDenied = { vm.permissionDenied() }) { vm.startPractice() }
    vm.importOutcome?.let { outcome ->
        AlertDialog(
            onDismissRequest = { vm.dismissImportOutcome() },
            title = { Text(if (outcome.succeeded) "Import finished" else "Import failed") },
            text = { Text(outcome.message) },
            confirmButton = { TextButton(onClick = { vm.dismissImportOutcome() }) { Text("OK") } },
        )
    }
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
        is Sheet.Conditions -> vm.session(s.sessionId)?.let { ConditionsSheet(vm, it, close) } ?: close()
        is Sheet.EditRoom -> vm.room(s.roomId)?.let { EditRoomSheet(vm, it, close) } ?: close()
        is Sheet.EditPosition -> EditPositionSheet(vm, s.roomId, s.positionId, close)
        is Sheet.EditSession -> vm.session(s.sessionId)?.let { EditSessionSheet(vm, it, close) } ?: close()
    }
}

/** The ⋮ menu in the top bar: secondary actions per screen, so the screens themselves stay short. */
@Composable
fun TopBarMenu(vm: AppViewModel, screen: Screen) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun export(roomId: String) {
        scope.launch { context.startActivity(Intent.createChooser(vm.exportRoom(roomId, emptyList()), "Export room")) }
    }
    // A permanent delete waits for this confirmation; archive needs none (it is undone from the same menu).
    var confirmDelete by remember { mutableStateOf<Screen?>(null) }
    // Edits and deletes wait for a running re-analysis or import (the menu itself is disabled during a capture).
    val canEdit = !vm.editingBlocked
    val canDelete = canEdit
    // System file picker: the user grants access to one file, so no storage permission is needed.
    val pickExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importExport(uri)
    }
    val items: List<Pair<String, () -> Unit>> = when (screen) {
        Screen.Home -> {
            val archived = vm.rooms().count { it.archived }
            listOfNotNull(
                "Microphone check" to { vm.navigate(Screen.Capability) },
                if (vm.importing) null else "Import export…" to {
                    pickExport.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                },
                if (archived > 0 || vm.showArchivedRooms) {
                    (if (vm.showArchivedRooms) "Hide archived rooms" else "Show archived rooms ($archived)") to
                        { vm.showArchivedRooms = !vm.showArchivedRooms }
                } else null,
            )
        }
        is Screen.RoomDetail -> {
            val room = vm.room(screen.roomId)
            val archivedSessions = vm.sessions(screen.roomId).count { it.archived }
            listOfNotNull(
                room?.takeIf { canEdit }?.let { "Edit room…" to { vm.sheet = Sheet.EditRoom(it.id) } },
                "Measurement protocol" to { vm.sheet = Sheet.Protocol },
                "Compare sessions" to { vm.navigate(Screen.Compare(screen.roomId)) },
                "Export all" to { export(screen.roomId) },
                if (archivedSessions > 0 || vm.showArchivedSessions) {
                    (if (vm.showArchivedSessions) "Hide archived sessions" else "Show archived sessions ($archivedSessions)") to
                        { vm.showArchivedSessions = !vm.showArchivedSessions }
                } else null,
                "Microphone check" to { vm.navigate(Screen.Capability) },
                room?.let {
                    if (it.archived) "Unarchive room" to { vm.setRoomArchived(it.id, false) }
                    else "Archive room (hide, keep data)" to { vm.setRoomArchived(it.id, true); vm.back() }
                },
                room?.takeIf { canDelete }?.let { "Delete room permanently…" to { confirmDelete = screen } },
            )
        }
        is Screen.SessionDetail -> {
            val session = vm.session(screen.sessionId)
            val roomId = session?.roomId
            listOfNotNull(
                session?.takeIf { canEdit }?.let { "Edit session…" to { vm.sheet = Sheet.EditSession(it.id) } },
                "How to clap" to { vm.sheet = Sheet.HowToClap },
                "Session conditions" to { vm.sheet = Sheet.Conditions(screen.sessionId) },
                roomId?.takeIf { canEdit }?.let { "Add position to room" to { vm.sheet = Sheet.AddPosition(it) } },
                "Measurement protocol" to { vm.sheet = Sheet.Protocol },
                session?.let {
                    if (it.archived) "Unarchive session" to { vm.setSessionArchived(it.id, false) }
                    else "Archive session (hide, keep data)" to { vm.setSessionArchived(it.id, true); vm.back() }
                },
                session?.takeIf { canDelete }?.let { "Delete session permanently…" to { confirmDelete = screen } },
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
    when (val target = confirmDelete) {
        is Screen.RoomDetail -> {
            val room = vm.room(target.roomId)
            if (room != null) {
                val sessions = vm.sessions(room.id)
                ConfirmPermanentDelete(
                    title = "Delete room?",
                    what = "“${room.name}”, its ${sessions.size} session(s) and ${sessions.sumOf { vm.attempts(it.id).size }} recording(s)",
                    onDelete = { confirmDelete = null; vm.deleteRoom(room.id) },
                    onExportFirst = { confirmDelete = null; export(room.id) },
                    onDismiss = { confirmDelete = null },
                )
            }
        }
        is Screen.SessionDetail -> {
            val session = vm.session(target.sessionId)
            if (session != null) {
                ConfirmPermanentDelete(
                    title = "Delete session?",
                    what = "“${session.name}” and its ${vm.attempts(session.id).size} recording(s)",
                    onDelete = { confirmDelete = null; vm.deleteSession(session.id) },
                    onExportFirst = { confirmDelete = null; export(session.roomId) },
                    onDismiss = { confirmDelete = null },
                )
            }
        }
        else -> Unit
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

@Composable
private fun SheetActions(confirm: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSessionSheet(vm: AppViewModel, roomId: String, onDismiss: () -> Unit) {
    val room = vm.room(roomId) ?: return onDismiss()
    val last = remember(roomId) { vm.lastConditions(roomId) }
    var kind by remember { mutableStateOf(vm.nextSessionKind(roomId)) }
    var name by remember { mutableStateOf(vm.defaultSessionName(roomId, kind, kindLabel(kind))) }
    var intervention by remember { mutableStateOf("") }
    val start = last ?: RoomConditions(appliancesOff = true)
    var doors by remember { mutableStateOf(start.doorsAndWindows) }
    var furnishings by remember { mutableStateOf(start.furnishings) }
    var occupancy by remember { mutableStateOf(start.occupancy) }
    var appliancesOff by remember { mutableStateOf(start.appliancesOff ?: true) }
    // First session: conditions are open so they get described once. Later: collapsed, prefilled from the last session.
    var conditionsOpen by remember { mutableStateOf(last == null) }

    KlangSheet(onDismiss) {
        SheetTitle("New session", "Records at all ${room.positions.size} positions of ${room.name}.")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SessionKind.entries.forEachIndexed { i, k ->
                SegmentedButton(
                    selected = kind == k,
                    onClick = { kind = k; name = vm.defaultSessionName(roomId, k, kindLabel(k)) },
                    shape = SegmentedButtonDefaults.itemShape(i, SessionKind.entries.size),
                    icon = {},
                    label = { Text(if (k == SessionKind.REPEAT_BASELINE) "Repeat" else kindLabel(k), maxLines = 1) },
                )
            }
        }
        Text(
            when (kind) {
                SessionKind.BASELINE -> "The room as it is now."
                SessionKind.REPEAT_BASELINE -> "Room unchanged, to see how much results vary on their own."
                SessionKind.TREATMENT -> "One change added to the room."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field(name, { name = it }, "Name")
        if (kind == SessionKind.TREATMENT) {
            Field(intervention, { intervention = it }, "What changed (required)", "e.g. one wall panel behind the table")
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Room conditions", style = MaterialTheme.typography.titleSmall)
                        val parts = listOfNotNull(
                            doors.takeIf { it.isNotBlank() }?.let { "doors/windows: $it" },
                            furnishings.takeIf { it.isNotBlank() },
                            occupancy.takeIf { it.isNotBlank() }?.let { "people: $it" },
                            if (appliancesOff) "appliances off" else "appliances on",
                        )
                        Text(
                            (if (last != null) "Same as last session: " else "") + parts.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { conditionsOpen = !conditionsOpen }) { Text(if (conditionsOpen) "Done" else "Edit") }
                }
                if (conditionsOpen) {
                    Field(doors, { doors = it }, "Doors and windows")
                    Field(furnishings, { furnishings = it }, "Furnishings")
                    Field(occupancy, { occupancy = it }, "People in the room")
                    ApplianceCheckbox(appliancesOff) { appliancesOff = it }
                }
            }
        }

        SheetActions("Start session", name.isNotBlank() && (kind != SessionKind.TREATMENT || intervention.isNotBlank()), onDismiss) {
            val s = vm.createSession(
                roomId, name.trim(), kind, if (kind == SessionKind.TREATMENT) intervention.trim() else "",
                RoomConditions(doors.trim(), furnishings.trim(), occupancy.trim(), appliancesOff),
            )
            vm.navigate(Screen.SessionDetail(s.id))
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

/** Shown in edit sheets while edits are refused (see [AppViewModel.editingBlocked]). */
@Composable
private fun EditingBlockedNote(vm: AppViewModel) {
    if (vm.editingBlocked) {
        Text(
            "Saving is paused while a recording, re-analysis or import runs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ApplianceCheckbox(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text("Dishwasher, extractor fan etc. off", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun EditRoomSheet(vm: AppViewModel, room: Room, onDismiss: () -> Unit) {
    var name by remember(room.id) { mutableStateOf(room.name) }
    var notes by remember(room.id) { mutableStateOf(room.notes) }
    KlangSheet(onDismiss) {
        SheetTitle("Edit room")
        Field(name, { name = it }, "Name")
        Field(notes, { notes = it }, "Notes (optional)", "Floor, windows, cabinetry…")
        EditingBlockedNote(vm)
        SheetActions("Save", name.isNotBlank() && !vm.editingBlocked, onDismiss) {
            if (vm.updateRoom(room.id, name.trim(), notes.trim())) onDismiss()
        }
    }
}

@Composable
private fun EditPositionSheet(vm: AppViewModel, roomId: String, positionId: String, onDismiss: () -> Unit) {
    val room = vm.room(roomId)
    val p = room?.positions?.firstOrNull { it.id == positionId } ?: return onDismiss()
    val impact = remember(roomId, positionId) { vm.positionDeleteImpact(roomId, positionId) }
    var name by remember(positionId) { mutableStateOf(p.name) }
    var placement by remember(positionId) { mutableStateOf(p.placementNotes) }
    var clapSpot by remember(positionId) { mutableStateOf(p.clapSpot) }
    KlangSheet(onDismiss) {
        SheetTitle("Edit position", "Belongs to ${room.name}.")
        Field(name, { name = it }, "Name")
        Field(placement, { placement = it }, "Phone placement (optional)", "Height, support, orientation")
        Field(clapSpot, { clapSpot = it }, "Clap spot (optional)", "e.g. 1.8 m from phone, toward kitchen")
        if (impact.recordings > 0) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    positionEditWarning(impact.recordings, impact.sessions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        EditingBlockedNote(vm)
        SheetActions("Save", name.isNotBlank() && !vm.editingBlocked, onDismiss) {
            if (vm.updatePosition(roomId, positionId, name.trim(), placement.trim(), clapSpot.trim())) onDismiss()
        }
    }
}

/** Shown when editing a position that already has recordings: the edit is not kept per session. */
fun positionEditWarning(recordings: Int, sessions: Int): String =
    "This position already has ${plural(recordings, "recording")} in ${plural(sessions, "session")}. " +
        "The change applies in place: older sessions will show the new description, not the one they were recorded with. " +
        "If the phone or clap spot really moved, add a new position instead."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSessionSheet(vm: AppViewModel, session: Session, onDismiss: () -> Unit) {
    var kind by remember(session.id) { mutableStateOf(session.kind) }
    var name by remember(session.id) { mutableStateOf(session.name) }
    var intervention by remember(session.id) { mutableStateOf(session.intervention) }
    KlangSheet(onDismiss) {
        SheetTitle("Edit session", "Recordings and their analyses are not changed.")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SessionKind.entries.forEachIndexed { i, k ->
                SegmentedButton(
                    selected = kind == k,
                    onClick = { kind = k },
                    shape = SegmentedButtonDefaults.itemShape(i, SessionKind.entries.size),
                    icon = {},
                    label = { Text(if (k == SessionKind.REPEAT_BASELINE) "Repeat" else kindLabel(k), maxLines = 1) },
                )
            }
        }
        Text(
            SESSION_KIND_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = if (kind != session.kind) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field(name, { name = it }, "Name")
        // Kept (not cleared) when the kind changes away from treatment, so nothing typed is lost.
        if (kind == SessionKind.TREATMENT || intervention.isNotBlank()) {
            Field(
                intervention, { intervention = it },
                if (kind == SessionKind.TREATMENT) "What changed (required)" else "What changed",
                "e.g. one wall panel behind the table",
            )
        }
        EditingBlockedNote(vm)
        SheetActions(
            "Save",
            name.isNotBlank() && (kind != SessionKind.TREATMENT || intervention.isNotBlank()) && !vm.editingBlocked,
            onDismiss,
        ) {
            if (vm.updateSession(session.id, name.trim(), kind, intervention.trim(), session.conditions)) onDismiss()
        }
    }
}

const val SESSION_KIND_NOTE =
    "The type decides how Compare uses this session: as the baseline, as the repeat baseline (unchanged room) or as a treatment. " +
        "Changing it changes which comparisons the session can be picked for."

/** The session's room conditions, prefilled and editable. A legacy per-session clap source is kept as it is. */
@Composable
private fun ConditionsSheet(vm: AppViewModel, session: Session, onDismiss: () -> Unit) {
    val c = session.conditions
    var doors by remember(session.id) { mutableStateOf(c.doorsAndWindows) }
    var furnishings by remember(session.id) { mutableStateOf(c.furnishings) }
    var occupancy by remember(session.id) { mutableStateOf(c.occupancy) }
    var appliancesOff by remember(session.id) { mutableStateOf(c.appliancesOff) }
    var notes by remember(session.id) { mutableStateOf(c.notes) }
    KlangSheet(onDismiss) {
        SheetTitle("Session conditions", kindLabel(session.kind) + if (session.intervention.isNotBlank()) " · ${session.intervention}" else "")
        Field(doors, { doors = it }, "Doors and windows")
        Field(furnishings, { furnishings = it }, "Furnishings")
        Field(occupancy, { occupancy = it }, "People in the room")
        ApplianceCheckbox(appliancesOff == true) { appliancesOff = it }
        Field(notes, { notes = it }, "Notes (optional)")
        // Sessions from before clap spots moved to positions may carry their own source description; it is kept as is.
        if (c.sourcePosition.isNotBlank()) {
            ListItem(
                overlineContent = { Text("Clap source (session, older format)") },
                headlineContent = { Text(c.sourcePosition) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        EditingBlockedNote(vm)
        SheetActions("Save", !vm.editingBlocked, onDismiss) {
            val edited = c.copy(
                doorsAndWindows = doors.trim(), furnishings = furnishings.trim(), occupancy = occupancy.trim(),
                appliancesOff = appliancesOff, notes = notes.trim(),
            )
            if (edited == c || vm.updateSession(session.id, session.name, session.kind, session.intervention, edited)) onDismiss()
        }
    }
}
