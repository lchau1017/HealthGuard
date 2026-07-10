@file:OptIn(ExperimentalMaterial3Api::class)

package com.healthguard.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthguard.common.ui.CategoryLabelInput

/**
 * The editable "Details" section: the six text fields, the with-food
 * selector, and the Save action. Every keystroke goes straight to the view
 * model as an intent; validation errors and the Save gate come back through
 * [DetailUiState].
 */
@Composable
fun DetailForm(
    state: DetailUiState,
    onIntent: (DetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Details")
        OutlinedTextField(
            value = state.name,
            onValueChange = { onIntent(DetailIntent.NameChanged(it)) },
            label = { Text("Drug name") },
            isError = state.nameError,
            supportingText = if (state.nameError) {
                { Text("A name is required") }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.dosage,
            onValueChange = { onIntent(DetailIntent.DosageChanged(it)) },
            label = { Text("Dosage") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.form,
            onValueChange = { onIntent(DetailIntent.FormChanged(it)) },
            label = { Text("Form") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        CategoryLabelInput(
            label = state.label,
            onLabelChange = { onIntent(DetailIntent.LabelChanged(it)) },
        )
        OutlinedTextField(
            value = state.ingredients,
            onValueChange = { onIntent(DetailIntent.IngredientsChanged(it)) },
            label = { Text("Active ingredients") },
            supportingText = { Text("Separate multiple ingredients with commas") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.frequencyText,
            onValueChange = { onIntent(DetailIntent.FrequencyChanged(it)) },
            label = { Text("Frequency") },
            isError = state.frequencyError,
            supportingText = {
                Text(
                    if (state.frequencyError) {
                        "Use \"2 times a day\" or \"every 6 hours\""
                    } else {
                        "e.g. \"2 times a day\" or \"every 6 hours\"; blank for none"
                    },
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Take with food?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WithFoodSelector(
            selected = state.withFood,
            onSelect = { onIntent(DetailIntent.WithFoodChanged(it)) },
        )

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { onIntent(DetailIntent.Save) },
            enabled = state.canSave,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
        ) {
            Text("Save", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun WithFoodSelector(
    selected: Boolean?,
    onSelect: (Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        "With food" to true,
        "Doesn't matter" to false,
        "Unknown" to null,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (label, value) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}
