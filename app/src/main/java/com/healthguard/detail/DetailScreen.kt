@file:OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)

package com.healthguard.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthguard.activity.HeatMapGrid
import com.healthguard.format.countdownTextSeconds
import com.healthguard.format.dayTimeLabel
import com.healthguard.format.doseAnnotation
import com.healthguard.format.lastTakenLabel
import com.healthguard.format.mediumDateLabel
import com.healthguard.format.timeLabel
import com.healthguard.format.toHumanText
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.domain.doseSlots
import com.healthguard.shared.extraction.Frequency
import com.healthguard.ui.CategoryChip
import com.healthguard.ui.CategoryLabelInput
import com.healthguard.ui.DeleteConfirmationDialog
import com.healthguard.ui.theme.heatRamp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Full-screen medication detail: identity header, live status card with the
 * guarded take-now action, the schedule summary, the editable "Details" form,
 * the completeness history, and the start/stop control at the bottom. Delete
 * lives in the top bar behind the shared confirmation dialog. The host
 * observes [DetailUiState.finished] to navigate back.
 */
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val takeConfirmMinutes by viewModel.takeConfirm.collectAsState()
    val recentTake by viewModel.recentTake.collectAsState()
    var confirmingDelete by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Catch takes recorded elsewhere (or a seeding pass) since this view
    // model was last active — dose writes don't retrigger its queries.
    LaunchedEffect(Unit) { viewModel.refresh() }

    // Per-second countdown: the detail page is where precision matters.
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Clock.System.now()
        }
    }
    val zone = remember { TimeZone.currentSystemDefault() }

    // Same undo contract as home: only the explicit action removes the dose.
    LaunchedEffect(recentTake) {
        val take = recentTake ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "${take.drugName} recorded",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoTake(take.doseId)
        } else {
            viewModel.clearRecentTake()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to medication list",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete this medication",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderBlock(state = state)

            if (state.isActive) {
                StatusCard(
                    state = state,
                    now = now,
                    zone = zone,
                    onTakeNow = viewModel::takeNow,
                )
            }

            ScheduleCard(state = state, zone = zone)

            SectionTitle("Details")
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
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
                onValueChange = viewModel::onDosageChange,
                label = { Text("Dosage") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.form,
                onValueChange = viewModel::onFormChange,
                label = { Text("Form") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            CategoryLabelInput(
                label = state.label,
                onLabelChange = viewModel::onLabelChange,
            )
            OutlinedTextField(
                value = state.ingredients,
                onValueChange = viewModel::onIngredientsChange,
                label = { Text("Active ingredients") },
                supportingText = { Text("Separate multiple ingredients with commas") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.frequencyText,
                onValueChange = viewModel::onFrequencyTextChange,
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
                onSelect = viewModel::onWithFoodChange,
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Save", style = MaterialTheme.typography.titleMedium)
            }

            HistorySection(
                history = state.history,
                doseDayStatuses = state.doseDayStatuses,
                adherencePercent = state.adherencePercent,
                historyFrom = state.historyFrom,
                now = now,
                zone = zone,
            )

            Spacer(Modifier.height(8.dp))
            if (state.isActive) {
                OutlinedButton(
                    onClick = viewModel::toggleTaking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Stop taking")
                }
            } else {
                Button(
                    onClick = viewModel::toggleTaking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Start taking")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    takeConfirmMinutes?.let { minutesAgo ->
        val ago = if (minutesAgo < 1) "moments ago" else "$minutesAgo minutes ago"
        AlertDialog(
            onDismissRequest = viewModel::dismissTakeConfirm,
            title = { Text("Record another dose?") },
            text = {
                Text(
                    "You recorded ${state.item?.medication?.drugName ?: "this medication"} " +
                        "$ago. Taking it again this soon may be a double dose.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmTakeAnyway) {
                    Text("Record anyway", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissTakeConfirm) {
                    Text("Cancel")
                }
            },
        )
    }

    if (confirmingDelete) {
        DeleteConfirmationDialog(
            medicationName = state.item?.medication?.drugName ?: "this medication",
            isActive = state.isActive,
            onConfirm = {
                confirmingDelete = false
                viewModel.delete()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/** Identity block: name, "500 mg · Capsule" line, category chip. */
@Composable
private fun HeaderBlock(state: DetailUiState, modifier: Modifier = Modifier) {
    val medication = state.item?.medication
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = medication?.drugName ?: "Medication",
            style = MaterialTheme.typography.headlineMedium,
        )
        val subtitle = listOfNotNull(
            medication?.dosage,
            medication?.form?.replaceFirstChar { it.uppercase() },
        ).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        medication?.label?.let { label ->
            Spacer(Modifier.height(6.dp))
            CategoryChip(label)
        }
    }
}

/** Live dose status: countdown, last take, and the guarded Take now action. */
@Composable
private fun StatusCard(
    state: DetailUiState,
    now: Instant,
    zone: TimeZone,
    onTakeNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (state.nextDoseAt != null) {
                Text(
                    text = "Next dose",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = countdownTextSeconds(state.nextDoseAt, now, zone),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            state.lastTakenAt?.let { lastTaken ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = lastTakenLabel(lastTaken, now, zone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onTakeNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Take now", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Schedule summary rows: dose times and the start date. */
@Composable
private fun ScheduleCard(
    state: DetailUiState,
    zone: TimeZone,
    modifier: Modifier = Modifier,
) {
    val schedule = state.item?.schedule ?: return
    val frequency = schedule.frequency
    val timesText = when (frequency) {
        null -> null
        is Frequency.EveryHours -> frequency.toHumanText().replaceFirstChar { it.uppercase() }
        is Frequency.TimesPerDay ->
            doseSlots(frequency).joinToString(" · ") { timeLabel(it) }
    }
    val startedText = schedule.startedAt
        ?.takeIf { state.isActive }
        ?.let { mediumDateLabel(it.toLocalDate(zone)) }
    if (timesText == null && startedText == null) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            timesText?.let { ScheduleRow(label = "Times", value = it) }
            startedText?.let { ScheduleRow(label = "Started", value = it) }
        }
    }
}

@Composable
private fun ScheduleRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Per-drug history: the adherence figure, a 16-week completeness heat map
 * (all taken / some / missed per day), then the latest dose logs.
 */
@Composable
private fun HistorySection(
    history: List<StoredDoseLog>,
    doseDayStatuses: Map<LocalDate, DayDoseStatus>,
    adherencePercent: Int?,
    historyFrom: LocalDate?,
    now: Instant,
    zone: TimeZone,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("History")
            adherencePercent?.let { percent ->
                Text(
                    text = "$percent% taken",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (history.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No doses recorded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        historyFrom?.let { from ->
            val ramp = heatRamp()
            Spacer(Modifier.height(8.dp))
            HeatMapGrid(
                from = from,
                today = now.toLocalDate(zone),
                cellColor = { date ->
                    when (doseDayStatuses[date]) {
                        DayDoseStatus.ALL -> ramp[4]
                        DayDoseStatus.SOME -> ramp[2]
                        DayDoseStatus.MISSED -> ramp[1]
                        null -> ramp[0]
                    }
                },
                cellLabel = { date ->
                    "$date: " + when (doseDayStatuses[date]) {
                        DayDoseStatus.ALL -> "all doses taken"
                        DayDoseStatus.SOME -> "some doses taken"
                        DayDoseStatus.MISSED -> "doses missed"
                        null -> "no doses"
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LegendChip(color = ramp[4], text = "All taken")
                LegendChip(color = ramp[2], text = "Some")
                LegendChip(color = ramp[1], text = "Missed")
            }
        }
        Spacer(Modifier.height(8.dp))
        history.forEach { log ->
            HistoryRow(log = log, now = now, zone = zone)
        }
    }
}

@Composable
private fun LegendChip(
    color: androidx.compose.ui.graphics.Color,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, MaterialTheme.shapes.extraSmall),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One recent dose: status circle, timestamp, and the on-time annotation. */
@Composable
private fun HistoryRow(
    log: StoredDoseLog,
    now: Instant,
    zone: TimeZone,
    modifier: Modifier = Modifier,
) {
    val taken = log.status == DoseStatus.TAKEN
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (taken) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = dayTimeLabel(log.takenAt ?: log.plannedAt, now, zone),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = doseAnnotation(log.status, log.plannedAt, log.takenAt),
                style = MaterialTheme.typography.bodySmall,
                color = when (log.status) {
                    DoseStatus.MISSED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp),
    )
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

private fun Instant.toLocalDate(zone: TimeZone): LocalDate = toLocalDateTime(zone).date
