@file:OptIn(ExperimentalMaterial3Api::class)

package com.healthguard.common.ui

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
import com.healthguard.common.theme.Spacing

/** The scan-flow source chooser: take a photo, or pick one from the gallery. */
@Composable
fun PhotoSourceSheet(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.sm)) {
            Text(
                text = "Add a medication label photo",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(Spacing.lg))
            Button(
                onClick = {
                    onDismiss()
                    onTakePhoto()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Take photo")
            }
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onPickFromGallery()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose from gallery")
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}
