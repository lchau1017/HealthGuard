package com.healthguard.confirm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.healthguard.common.ui.CategoryLabelInput
import com.healthguard.confirm.ConfirmIntent
import com.healthguard.confirm.ConfirmUiState
import com.healthguard.confirm.ReviewField

/**
 * The import/confirm flow, presented as a full-width dialog card over the
 * home screen: progress while the label is being read, the editable review
 * list (plus an optional category label) on success, and a retriable error
 * otherwise. Fields the model was unsure about are flagged and must be edited
 * or confirmed before Accept unlocks.
 *
 * The scrim never dismisses (an accidental tap must not throw away edits);
 * only the back gesture or the explicit Cancel button raises [ConfirmIntent.Reset].
 * The host is expected to hide this dialog when the state is Idle.
 */
@Composable
fun ConfirmDialog(
    state: ConfirmUiState,
    onIntent: (ConfirmIntent) -> Unit,
) {
    Dialog(
        onDismissRequest = { onIntent(ConfirmIntent.Reset) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .systemBarsPadding()
                .imePadding()
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            when (val current = state) {
                is ConfirmUiState.Idle, is ConfirmUiState.Extracting -> ExtractingContent()
                is ConfirmUiState.Review -> ReviewContent(
                    fields = current.fields,
                    label = current.label,
                    canAccept = current.canAccept,
                    onFieldEdited = { key, value -> onIntent(ConfirmIntent.FieldEdited(key, value)) },
                    onFieldConfirmed = { key -> onIntent(ConfirmIntent.FieldConfirmed(key)) },
                    onLabelChange = { onIntent(ConfirmIntent.LabelChanged(it)) },
                    onAccept = { onIntent(ConfirmIntent.Accept) },
                    onCancel = { onIntent(ConfirmIntent.Reset) },
                )
                is ConfirmUiState.Error -> ErrorContent(
                    message = current.message,
                    retriable = current.retriable,
                    onRetry = { onIntent(ConfirmIntent.Retry) },
                    onCancel = { onIntent(ConfirmIntent.Reset) },
                )
            }
        }
    }
}

@Composable
private fun ExtractingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Reading label…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ReviewContent(
    fields: List<ReviewField>,
    label: String,
    canAccept: Boolean,
    onFieldEdited: (String, String) -> Unit,
    onFieldConfirmed: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text("Check the details", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Fields marked with a warning need your review before saving.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        fields.forEach { field ->
            ReviewFieldRow(
                field = field,
                onValueChange = { onFieldEdited(field.key, it) },
                onConfirm = { onFieldConfirmed(field.key) },
            )
            Spacer(Modifier.height(12.dp))
        }

        // The label is business data and lives in the Review state (like the
        // detail form's LabelChanged), so it survives the composition dying —
        // an Error → Retry round-trip included — instead of resetting.
        CategoryLabelInput(
            label = label,
            onLabelChange = onLabelChange,
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onAccept,
            enabled = canAccept,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Accept")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@Composable
private fun ReviewFieldRow(
    field: ReviewField,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val flagged = field.needsReview && !field.userConfirmed
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = field.value,
            onValueChange = onValueChange,
            label = { Text(field.label) },
            isError = flagged,
            supportingText = if (flagged) {
                { Text("Low confidence — please check") }
            } else {
                null
            },
            trailingIcon = if (flagged) {
                { Text("⚠", color = MaterialTheme.colorScheme.error) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (flagged) {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Looks right")
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    retriable: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "⚠",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (retriable) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Retry")
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}
