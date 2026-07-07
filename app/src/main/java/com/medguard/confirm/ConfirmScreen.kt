package com.medguard.confirm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Renders the extraction flow: progress while the label is being read, the
 * editable review list on success, and a retriable error otherwise. Fields
 * the model was unsure about are flagged and must be edited or confirmed
 * before Accept unlocks.
 */
@Composable
fun ConfirmScreen(
    viewModel: ConfirmViewModel,
    onAccept: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    when (val current = state) {
        is ConfirmUiState.Idle, is ConfirmUiState.Extracting -> ExtractingContent(modifier)
        is ConfirmUiState.Review -> ReviewContent(
            fields = current.fields,
            canAccept = viewModel.canAccept,
            onFieldEdited = viewModel::onFieldEdited,
            onFieldConfirmed = viewModel::onFieldConfirmed,
            onAccept = onAccept,
            onBack = onBack,
            modifier = modifier,
        )
        is ConfirmUiState.Error -> ErrorContent(
            message = current.message,
            retriable = current.retriable,
            onRetry = viewModel::onRetry,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@Composable
private fun ExtractingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
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
    canAccept: Boolean,
    onFieldEdited: (String, String) -> Unit,
    onFieldConfirmed: (String) -> Unit,
    onAccept: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
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

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onAccept,
            enabled = canAccept,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Accept")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
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
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
