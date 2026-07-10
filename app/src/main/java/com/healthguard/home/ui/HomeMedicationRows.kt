@file:OptIn(ExperimentalMaterial3Api::class)

package com.healthguard.home.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.healthguard.common.theme.Spacing
import com.healthguard.common.ui.CategoryChip
import com.healthguard.common.ui.StatusChip
import com.healthguard.common.ui.semanticsLabel
import com.healthguard.home.MedicationPhase
import com.healthguard.home.format.DoseRowStatus
import com.healthguard.home.state.CabinetRow
import com.healthguard.home.state.DoseCard

/** One "Taking now" row: category avatar, name, chip + form, trailing status. */
@Composable
internal fun TakingRow(
    card: DoseCard,
    onTakeNow: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MedicationRowCard(
        title = card.title,
        drugName = card.drugName,
        categoryLabel = card.categoryLabel,
        formLabel = card.formLabel,
        onClick = onClick,
        modifier = modifier,
    ) {
        Spacer(Modifier.width(Spacing.md))
        when (val status = card.status) {
            DoseRowStatus.Due -> FilledTonalButton(
                onClick = onTakeNow,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .semanticsLabel("Take ${card.drugName} now"),
            ) {
                Text("Take")
            }
            DoseRowStatus.TakenForToday -> Text(
                text = "Taken ✓",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            is DoseRowStatus.Next -> Text(
                text = status.text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DoseRowStatus.None -> Unit
        }
    }
}

/**
 * Cabinet rows share the taking-row look plus a phase chip — "Not started"
 * or "Stopped 3 Jul" — so the two dormant states read differently at a
 * glance; the play affordance starts (or resumes) tracking.
 */
@Composable
internal fun CabinetRowCard(
    row: CabinetRow,
    onPlay: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MedicationRowCard(
        title = row.title,
        drugName = row.drugName,
        categoryLabel = row.categoryLabel,
        formLabel = row.formLabel,
        onClick = onClick,
        modifier = modifier,
        leadingChip = {
            row.phaseChipText?.let { text ->
                StatusChip(
                    text = text,
                    outlined = row.phase == MedicationPhase.NOT_STARTED,
                )
                Spacer(Modifier.width(6.dp))
            }
        },
    ) {
        val verb = if (row.phase == MedicationPhase.STOPPED) "Resume" else "Start"
        FilledTonalIconButton(
            onClick = onPlay,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "$verb taking ${row.drugName}",
            )
        }
    }
}

/**
 * The one medication-row card behind [TakingRow] and [CabinetRowCard]:
 * a clickable card opening the detail page, with the category avatar, the
 * clamped title, the chips-and-form line (optionally led by [leadingChip])
 * and a [trailing] slot for the row's action or status.
 */
@Composable
private fun MedicationRowCard(
    title: String,
    drugName: String,
    categoryLabel: String?,
    formLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingChip: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semanticsLabel("$drugName, open details"),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillAvatar(label = categoryLabel)
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    // Real labels produce dosage prose ("Take 1 or 2 caplets up
                    // to 3 times a day, as required.") — clamp the row.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    leadingChip()
                    categoryLabel?.let { label ->
                        CategoryChip(label)
                        Spacer(Modifier.width(6.dp))
                    }
                    formLabel?.let { form ->
                        Text(
                            text = form,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            trailing()
        }
    }
}
