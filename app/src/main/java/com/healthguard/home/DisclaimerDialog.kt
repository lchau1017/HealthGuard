package com.healthguard.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The about/medical-disclaimer dialog behind the top-bar info action. */
@Composable
fun DisclaimerDialog(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HealthGuard is an information tool") },
        text = {
            Text(
                "HealthGuard helps you keep track of your medications. It is " +
                    "not medical advice. Always consult your doctor or " +
                    "pharmacist about dosing, interactions, and side effects.",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
        modifier = modifier,
    )
}
