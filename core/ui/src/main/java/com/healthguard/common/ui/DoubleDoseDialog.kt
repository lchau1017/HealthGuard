package com.healthguard.common.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The one double-dose confirmation used everywhere a dose can be recorded
 * (the home Take actions and the detail Take now button), so wording and
 * emphasis never drift between entry points. [minutesAgo] is how long ago
 * the previous take was recorded; under a minute it reads "moments ago".
 */
@Composable
fun DoubleDoseDialog(
    drugName: String,
    minutesAgo: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ago = if (minutesAgo < 1) "moments ago" else "$minutesAgo minutes ago"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record another dose?") },
        text = {
            Text(
                "You recorded $drugName $ago. Taking it again this soon " +
                    "may be a double dose.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Record anyway", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
