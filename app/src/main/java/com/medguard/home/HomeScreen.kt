@file:OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)

package com.medguard.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medguard.dose.RecordedTake
import com.medguard.format.countdownText
import com.medguard.format.doseTimeText
import com.medguard.format.toHumanText
import com.medguard.shared.data.MedicationWithSchedule
import com.medguard.ui.DeleteConfirmationDialog
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * The home screen: a "Next dose" hero card, the active "Taking now" list,
 * and the dormant "My cabinet" below, with the scan flow on an extended FAB.
 * Every card and row opens the medication detail page.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    takeConfirm: TakeConfirmation?,
    recentTake: RecordedTake?,
    onTakeNow: (DoseCard) -> Unit,
    onConfirmTakeAnyway: () -> Unit,
    onDismissTakeConfirm: () -> Unit,
    onUndoTake: (String) -> Unit,
    onRecentTakeHandled: () -> Unit,
    onPlay: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSourceSheet by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<MedicationWithSchedule?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Fresh wall-clock reading per state emission: the view model re-emits at
    // least every minute, which is exactly the countdown's resolution.
    val now = remember(state) { Clock.System.now() }
    val zone = remember { TimeZone.currentSystemDefault() }

    // One undo snackbar per recorded take; timing out or dismissing keeps
    // the dose, only the explicit Undo action removes it.
    LaunchedEffect(recentTake) {
        val take = recentTake ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "${take.drugName} recorded",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            onUndoTake(take.doseId)
        } else {
            onRecentTakeHandled()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("MedGuard") },
                actions = {
                    IconButton(onClick = { showDisclaimer = true }) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "About MedGuard and medical disclaimer",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showSourceSheet = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Scan medication") },
                modifier = Modifier.semanticsLabel("Scan a medication label"),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "disclaimer-line") {
                Text(
                    text = "Information tool — not medical advice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.nextUp?.let { hero ->
                item(key = "hero") {
                    NextDoseHeroCard(
                        card = hero,
                        moreDueCount = if (state.dueCount >= 2) state.dueCount - 1 else 0,
                        now = now,
                        zone = zone,
                        onTakeNow = { onTakeNow(hero) },
                        onClick = { onOpenDetail(hero.item.medication.id) },
                    )
                }
            }

            if (state.taking.isEmpty() && state.cabinet.isEmpty()) {
                item(key = "empty-all") { OverallEmptyState() }
            } else {
                item(key = "taking-header") { SectionHeader("Taking now") }
                if (state.taking.isEmpty()) {
                    item(key = "taking-empty") {
                        SectionEmptyText(
                            "Nothing active yet — press play on a cabinet " +
                                "medication to start tracking doses.",
                        )
                    }
                } else {
                    items(state.taking, key = { "taking-${it.item.medication.id}" }) { card ->
                        TakingCard(
                            card = card,
                            now = now,
                            zone = zone,
                            onTakeNow = { onTakeNow(card) },
                            onClick = { onOpenDetail(card.item.medication.id) },
                        )
                    }
                }

                item(key = "cabinet-header") { SectionHeader("My cabinet") }
                if (state.cabinet.isEmpty()) {
                    item(key = "cabinet-empty") {
                        SectionEmptyText("Everything you scanned is in use.")
                    }
                } else {
                    items(state.cabinet, key = { "cabinet-${it.medication.id}" }) { row ->
                        CabinetRow(
                            row = row,
                            onPlay = { onPlay(row.medication.id) },
                            onDelete = { pendingDelete = row },
                            onClick = { onOpenDetail(row.medication.id) },
                        )
                    }
                }
            }
            item(key = "fab-spacer") { Spacer(Modifier.height(72.dp)) }
        }
    }

    takeConfirm?.let { confirm ->
        DoubleDoseDialog(
            drugName = confirm.card.item.medication.drugName,
            minutesAgo = confirm.minutesAgo,
            onConfirm = onConfirmTakeAnyway,
            onDismiss = onDismissTakeConfirm,
        )
    }

    pendingDelete?.let { row ->
        DeleteConfirmationDialog(
            medicationName = row.medication.drugName,
            isActive = row.isActive,
            onConfirm = {
                onDelete(row.medication.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text("MedGuard is an information tool") },
            text = {
                Text(
                    "MedGuard helps you keep track of your medications. It is " +
                        "not medical advice. Always consult your doctor or " +
                        "pharmacist about dosing, interactions, and side effects.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showDisclaimer = false }) { Text("Got it") }
            },
        )
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

private enum class Urgency { OVERDUE, DUE_SOON, UPCOMING, NONE }

private fun urgencyOf(nextDoseAt: Instant?, now: Instant): Urgency {
    val untilDose = (nextDoseAt ?: return Urgency.NONE) - now
    return when {
        untilDose <= (-1).minutes -> Urgency.OVERDUE
        untilDose <= 30.minutes -> Urgency.DUE_SOON
        else -> Urgency.UPCOMING
    }
}

@Composable
private fun Urgency.countdownColor(): Color = when (this) {
    Urgency.OVERDUE -> MaterialTheme.colorScheme.error
    Urgency.DUE_SOON -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun NextDoseHeroCard(
    card: DoseCard,
    moreDueCount: Int,
    now: Instant,
    zone: TimeZone,
    onTakeNow: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val medication = card.item.medication
    val urgency = urgencyOf(card.nextDoseAt, now)
    val containerColor = when (urgency) {
        Urgency.OVERDUE -> MaterialTheme.colorScheme.errorContainer
        Urgency.DUE_SOON -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = modifier
            .fillMaxWidth()
            .semanticsLabel("Next dose: ${medication.drugName}, open details"),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Next dose",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(medication.drugName, medication.dosage)
                    .joinToString(" · "),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = card.nextDoseAt?.let { doseTimeText(it, zone) }.orEmpty(),
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = countdownText(card.nextDoseAt, now, zone),
                    style = MaterialTheme.typography.titleMedium,
                    color = urgency.countdownColor(),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            card.item.schedule.frequency?.let { frequency ->
                Text(
                    text = frequency.toHumanText() +
                        if (card.item.schedule.withFood == true) " · with food" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (moreDueCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "and $moreDueCount more due now",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onTakeNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .semanticsLabel("Take ${medication.drugName} now"),
            ) {
                Text("Take now", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun TakingCard(
    card: DoseCard,
    now: Instant,
    zone: TimeZone,
    onTakeNow: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val medication = card.item.medication
    val schedule = card.item.schedule
    val urgency = urgencyOf(card.nextDoseAt, now)
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semanticsLabel("${medication.drugName}, open details"),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOfNotNull(medication.drugName, medication.dosage)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                )
                schedule.frequency?.let { frequency ->
                    Text(
                        text = frequency.toHumanText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (schedule.withFood == true) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Take with food",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                medication.label?.let { label ->
                    AssistChip(onClick = onClick, label = { Text(label) })
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = card.nextDoseAt?.let { doseTimeText(it, zone) }.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = countdownText(card.nextDoseAt, now, zone),
                    style = MaterialTheme.typography.labelLarge,
                    color = urgency.countdownColor(),
                )
                if (card.isDue) {
                    Spacer(Modifier.height(4.dp))
                    FilledTonalButton(
                        onClick = onTakeNow,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semanticsLabel("Take ${medication.drugName} now"),
                    ) {
                        Text("Take")
                    }
                }
            }
        }
    }
}

@Composable
private fun DoubleDoseDialog(
    drugName: String,
    minutesAgo: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ago = if (minutesAgo < 1) "moments ago" else "$minutesAgo minutes ago"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record another dose?") },
        text = {
            Text(
                "You recorded $drugName $ago. Taking it again this soon " +
                    "may be a double dose.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Record anyway", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun CabinetRow(
    row: MedicationWithSchedule,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val medication = row.medication
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(onClick = onClick)
            .semanticsLabel("${medication.drugName}, open details")
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = listOfNotNull(medication.drugName, medication.dosage)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
            )
            medication.label?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilledTonalIconButton(
            onClick = onPlay,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Start taking ${medication.drugName}",
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete ${medication.drugName}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SectionEmptyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun OverallEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
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

/** Content-description shorthand: every actionable element must announce itself. */
private fun Modifier.semanticsLabel(label: String): Modifier =
    semantics { contentDescription = label }
