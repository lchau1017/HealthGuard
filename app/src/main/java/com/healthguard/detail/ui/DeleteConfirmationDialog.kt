package com.healthguard.detail.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The one delete confirmation used everywhere a medication can be removed
 * (home cabinet rows and the detail page), so wording and emphasis never
 * drift between entry points. Deleting also removes dose history, which is
 * why this always interrupts.
 */
@Composable
fun DeleteConfirmationDialog(
    medicationName: String,
    isActive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val body = buildString {
        append("This removes the medication and its dose history.")
        if (isActive) append(" You are currently taking this.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text("Delete $medicationName?") },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
