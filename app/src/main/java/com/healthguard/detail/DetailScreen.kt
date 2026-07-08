@file:OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)

package com.healthguard.detail

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Text
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
import com.healthguard.activity.ActivityHeatMap
import com.healthguard.activity.DayCount
import com.healthguard.activity.dayLabel
import com.healthguard.format.countdownTextSeconds
import com.healthguard.format.doseTimeText
import com.healthguard.format.lastTakenText
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import kotlinx.coroutines.delay
import com.healthguard.ui.CategoryLabelInput
import com.healthguard.ui.DeleteConfirmationDialog
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Full-screen medication detail: schedule status (start/stop + next dose) on
 * top, the editable fields below, Save at the bottom. Delete lives in the top
 * bar behind the shared confirmation dialog. The host observes
 * [DetailUiState.finished] to navigate back.
 */
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var confirmingDelete by remember { mutableStateOf(false) }

    // Per-second countdown: the detail page is where precision matters.
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Clock.System.now()
        }
    }
    val zone = remember { TimeZone.currentSystemDefault() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.item?.medication?.drugName ?: "Medication",
                        maxLines = 1,
                    )
                },
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
            ScheduleStatusCard(
                state = state,
                nowText = state.nextDoseAt?.let { doseTimeText(it, zone) }.orEmpty(),
                countdown = countdownTextSeconds(state.nextDoseAt, now, zone),
                lastTaken = state.lastTakenAt?.let { lastTakenText(it, now, zone) },
                onToggleTaking = viewModel::toggleTaking,
            )

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
                doseDayCounts = state.doseDayCounts,
                historyFrom = state.historyFrom,
                now = now,
                zone = zone,
            )
            Spacer(Modifier.height(20.dp))
        }
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

/**
 * Per-drug take history: a 16-week heat map with a tap-to-read caption, then
 * the latest dose logs with status and timestamp.
 */
@Composable
private fun HistorySection(
    history: List<StoredDoseLog>,
    doseDayCounts: List<DayCount>,
    historyFrom: LocalDate?,
    now: kotlin.time.Instant,
    zone: TimeZone,
    modifier: Modifier = Modifier,
) {
    var selectedDay by remember { mutableStateOf<Pair<LocalDate, Int>?>(null) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "History",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
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
            Spacer(Modifier.height(8.dp))
            ActivityHeatMap(
                dayCounts = doseDayCounts,
                from = from,
                today = now.toLocalDateTime(zone).date,
                onDayClick = { date, count -> selectedDay = date to count },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = selectedDay?.let { (date, count) ->
                    val doses = if (count == 1) "1 dose" else "$count doses"
                    "${dayLabel(date)} — $doses"
                } ?: "Tap a day for its count",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        history.forEach { log ->
            HistoryRow(log = log, now = now, zone = zone)
        }
    }
}

@Composable
private fun HistoryRow(
    log: StoredDoseLog,
    now: kotlin.time.Instant,
    zone: TimeZone,
    modifier: Modifier = Modifier,
) {
    val (statusLabel, statusColor) = when (log.status) {
        DoseStatus.TAKEN -> "Taken" to MaterialTheme.colorScheme.primary
        DoseStatus.SKIPPED -> "Skipped" to MaterialTheme.colorScheme.tertiary
        DoseStatus.MISSED -> "Missed" to MaterialTheme.colorScheme.error
        DoseStatus.PENDING -> "Pending" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(statusColor, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = lastTakenText(log.takenAt ?: log.plannedAt, now, zone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScheduleStatusCard(
    state: DetailUiState,
    nowText: String,
    countdown: String,
    lastTaken: String?,
    onToggleTaking: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (state.isActive) "Currently taking" else "Not taking",
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.isActive && state.nextDoseAt != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = nowText,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = countdown,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    text = "Next dose",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isActive && lastTaken != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Last taken: $lastTaken",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (state.isActive) {
                OutlinedButton(
                    onClick = onToggleTaking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Stop taking")
                }
            } else {
                Button(
                    onClick = onToggleTaking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Start taking")
                }
            }
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
