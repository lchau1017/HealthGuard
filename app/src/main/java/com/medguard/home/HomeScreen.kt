package com.medguard.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medguard.format.toHumanText
import com.medguard.shared.data.MedicationWithSchedule

/**
 * The single screen of the app: the medication list, with the import entry
 * point (camera or gallery) on top. Active medications float to the top with
 * a "Taking" badge and a stop control; dormant ones offer play-to-activate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    medications: List<MedicationWithSchedule>,
    onPlay: (String) -> Unit,
    onStop: (String) -> Unit,
    onDelete: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSourceSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "MedGuard",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Information tool — not medical advice. " +
                "Always consult your doctor or pharmacist.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { showSourceSheet = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("Import medication", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))

        if (medications.isEmpty()) {
            EmptyState(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(medications, key = { it.medication.id }) { row ->
                    MedicationRow(
                        row = row,
                        onPlay = { onPlay(row.medication.id) },
                        onStop = { onStop(row.medication.id) },
                        onDelete = { onDelete(row.medication.id) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    if (showSourceSheet) {
        ModalBottomSheet(onDismissRequest = { showSourceSheet = false }) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(
                    text = "Add a medication label photo",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        showSourceSheet = false
                        onTakePhoto()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Take photo")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        showSourceSheet = false
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
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Scan your first medication",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Take a photo of a medication label and it will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MedicationRow(
    row: MedicationWithSchedule,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val medication = row.medication
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOfNotNull(medication.drugName, medication.dosage)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                )
                row.schedule.frequency?.let { frequency ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = frequency.toHumanText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.isActive) {
                        Spacer(Modifier.height(4.dp))
                        TakingBadge()
                        Spacer(Modifier.width(8.dp))
                    }
                    medication.label?.let { label ->
                        Spacer(Modifier.height(4.dp))
                        LabelChip(label)
                    }
                }
            }
            if (row.isActive) {
                FilledTonalIconButton(onClick = onStop) {
                    StopIcon(contentDescription = "Stop taking ${medication.drugName}")
                }
            } else {
                FilledTonalIconButton(onClick = onPlay) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Start taking ${medication.drugName}",
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete ${medication.drugName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TakingBadge(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Taking",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LabelChip(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Material's core icon set has no stop symbol; a filled rounded square is the
 * universal one and avoids pulling in the extended icons artifact.
 */
@Composable
private fun StopIcon(contentDescription: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            .size(12.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
    )
}
