package se.klang.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Confirmation before a permanent delete. Says what is lost, that it cannot be undone, and offers
 * to export first (the export ZIP is the only copy that survives) or to archive instead.
 * [detail] adds a consequence specific to the item; [canArchive] is false for items that have no archive.
 */
@Composable
fun ConfirmPermanentDelete(
    title: String,
    what: String,
    onDelete: () -> Unit,
    onExportFirst: () -> Unit,
    onDismiss: () -> Unit,
    detail: String? = null,
    canArchive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$what will be removed from this phone permanently, including the original recordings and every analysis of them. This cannot be undone.")
                if (detail != null) Text(detail)
                Text(
                    "Export the room first if you may want the recordings later." +
                        if (canArchive) " Archiving hides it instead and keeps everything." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) } },
        dismissButton = {
            Row {
                TextButton(onClick = onExportFirst) { Text("Export first") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** What the delete confirmation of a position says is lost (counts from [se.klang.app.AppViewModel.positionDeleteImpact]). */
fun positionDeleteWhat(name: String, recordings: Int, sessions: Int): String = when (recordings) {
    0 -> "Position “$name” (it has no recordings)"
    else -> "Position “$name” with its ${plural(recordings, "recording")} in ${plural(sessions, "session")}"
}

/** Why deleting a position matters beyond its own recordings. */
const val POSITION_DELETE_DETAIL =
    "The position disappears from this room and from every comparison of it, because comparisons match positions by id. " +
        "Clap and background recordings at the other positions are kept."

fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
