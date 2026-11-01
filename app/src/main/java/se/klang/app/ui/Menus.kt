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
 */
@Composable
fun ConfirmPermanentDelete(
    title: String,
    what: String,
    onDelete: () -> Unit,
    onExportFirst: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$what will be removed from this phone permanently, including the original recordings and every analysis of them. This cannot be undone.")
                Text(
                    "Export the room first if you may want the recordings later. Archiving hides it instead and keeps everything.",
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
