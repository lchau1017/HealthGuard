@file:OptIn(ExperimentalLayoutApi::class)

package com.healthguard.common.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthguard.common.theme.CATEGORY_PRESETS

/**
 * Label picker: preset category chips plus a free-text override, all writing
 * the same single label string (stored in the medication's label column).
 * A chip is selected while the label equals its preset; tapping the selected
 * chip clears the label. The text field shows only non-preset labels — typing
 * a custom label overrides (deselects) any chip.
 */
@Composable
fun CategoryLabelInput(
    label: String,
    onLabelChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trimmed = label.trim()
    val isPreset = trimmed in CATEGORY_PRESETS
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Category",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CATEGORY_PRESETS.forEach { preset ->
                val selected = trimmed == preset
                FilterChip(
                    selected = selected,
                    onClick = { onLabelChange(if (selected) "" else preset) },
                    label = { Text(preset) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = if (isPreset) "" else label,
            onValueChange = onLabelChange,
            label = { Text("Custom label") },
            supportingText = { Text("Overrides the category chips, e.g. Heart") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
