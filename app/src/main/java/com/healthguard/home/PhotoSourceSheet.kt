@file:OptIn(ExperimentalMaterial3Api::class)

package com.healthguard.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The scan-flow source chooser: take a photo, or pick one from the gallery. */
@Composable
fun PhotoSourceSheet(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = "Add a medication label photo",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onDismiss()
                    onTakePhoto()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Take photo")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onPickFromGallery()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose from gallery")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
