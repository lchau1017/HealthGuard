@file:OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.healthguard.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.healthguard.activity.ActivityHeatMap
import com.healthguard.activity.AdherenceResult
import com.healthguard.activity.DayCount
import com.healthguard.activity.DayDetailSheet
import com.healthguard.activity.DoseDayStatus
import com.healthguard.activity.HeatMapGrid
import com.healthguard.common.format.timeLabel
import com.healthguard.common.format.toHumanText
import com.healthguard.home.MedicationPhase
import com.healthguard.home.phaseChipText
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.domain.doseSlots
import com.healthguard.shared.extraction.Frequency
import com.healthguard.common.ui.CategoryChip
import com.healthguard.common.ui.CategoryLabelInput
import com.healthguard.common.ui.StatusChip
import com.healthguard.common.theme.heatRamp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Full-screen medication detail: identity header, live status card with the
 * guarded take-now action, the schedule summary, the editable "Details" form,
 * the completeness history, and the start/stop control at the bottom. Delete
 * lives in the top bar behind the shared confirmation dialog. This screen is
 * the sole consumer of [effects]: the undo snackbar is shown here, and a
 * [DetailEffect.Finished] is handed to the host via [onFinished] (toast +
 * navigate back); [onBack] is the plain back-button/BackHandler path.
 */
@Composable
fun DetailScreen(
    state: DetailUiState,
    onIntent: (DetailIntent) -> Unit,
    effects: Flow<DetailEffect>,
    onBack: () -> Unit,
    onFinished: (DetailFinished) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Everything minute-grained renders from state.now — the clock the
    // tracked facts were computed against. Only the countdown text needs
    // seconds; it owns its own ticker (LiveCountdown), so the rest of the
    // screen never recomposes on a tick.
    val zone = remember { TimeZone.currentSystemDefault() }

    // Same undo contract as home: only the explicit action removes the dose.
    // Finished hands off to the host (toast + navigate back). Collected only
    // while STARTED; the Channel-backed effects are buffered, so anything
    // emitted while STOPPED is delivered when collection resumes.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(effects, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            effects.collect { effect ->
                when (effect) {
                    is DetailEffect.ShowUndoSnackbar -> {
                        val result = snackbarHostState.showSnackbar(
                            message = "${effect.take.drugName} recorded",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            onIntent(DetailIntent.UndoTake(effect.take.doseId))
                        }
                    }
                    is DetailEffect.Finished -> onFinished(effect.result)
                }
            }
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
            HeaderBlock(state = state, now = state.now, zone = zone)

            if (state.isActive) {
                StatusCard(
                    state = state,
                    zone = zone,
                    onTakeNow = { onIntent(DetailIntent.TakeNow) },
                )
            }

            ScheduleCard(state = state, zone = zone)

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

            HistorySection(
                history = state.history,
                dayStatuses = state.dayStatuses,
                dayTakeCounts = state.dayTakeCounts,
                adherence = state.adherence,
                isAsNeeded = state.isAsNeeded,
                historyFrom = state.historyFrom,
                now = state.now,
                zone = zone,
                onDayClick = { onIntent(DetailIntent.SelectDay(it)) },
            )

            Spacer(Modifier.height(8.dp))
            if (state.isActive) {
                OutlinedButton(
                    onClick = { onIntent(DetailIntent.ToggleTaking) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Stop taking")
                }
            } else {
                Button(
                    onClick = { onIntent(DetailIntent.ToggleTaking) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(
                        if (state.phase == MedicationPhase.STOPPED) {
                            "Resume taking"
                        } else {
                            "Start taking"
                        },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    state.takeConfirm?.let { minutesAgo ->
        val ago = if (minutesAgo < 1) "moments ago" else "$minutesAgo minutes ago"
        AlertDialog(
            onDismissRequest = { onIntent(DetailIntent.DismissTakeConfirm) },
            title = { Text("Record another dose?") },
            text = {
                Text(
                    "You recorded ${state.item?.medication?.drugName ?: "this medication"} " +
                        "$ago. Taking it again this soon may be a double dose.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onIntent(DetailIntent.ConfirmTakeAnyway) }) {
                    Text("Record anyway", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(DetailIntent.DismissTakeConfirm) }) {
                    Text("Cancel")
                }
            },
        )
    }

    state.dayDetail?.let { detail ->
        DayDetailSheet(
            detail = detail,
            onDismiss = { onIntent(DetailIntent.DismissDayDetail) },
        )
    }

    if (confirmingDelete) {
        DeleteConfirmationDialog(
            medicationName = state.item?.medication?.drugName ?: "this medication",
            isActive = state.isActive,
            onConfirm = {
                confirmingDelete = false
                onIntent(DetailIntent.Delete)
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/**
 * Identity block: name, "500 mg · Capsule" line, category chip, and — while
 * the medication is not actively taken — the phase chip ("Not started" /
 * "Stopped 3 Jul").
 */
@Composable
private fun HeaderBlock(
    state: DetailUiState,
    now: Instant,
    zone: TimeZone,
    modifier: Modifier = Modifier,
) {
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
        val phaseText = state.item?.schedule?.let { phaseChipText(it, now, zone) }
        if (medication?.label != null || phaseText != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                medication?.label?.let { label ->
                    CategoryChip(label)
                    Spacer(Modifier.width(6.dp))
                }
                phaseText?.let { text ->
                    StatusChip(
                        text = text,
                        outlined = state.phase == MedicationPhase.NOT_STARTED,
                    )
                }
            }
        }
    }
}

/** Live dose status: countdown, last take, and the guarded Take now action. */
@Composable
private fun StatusCard(
    state: DetailUiState,
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
                LiveCountdown(nextDoseAt = state.nextDoseAt, zone = zone)
            }
            state.lastTakenAt?.let { lastTaken ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = lastTakenLabel(lastTaken, state.now, zone),
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

/**
 * The per-second countdown to the next dose — the detail page is where that
 * precision matters. This composable owns its own ticking clock, so the
 * every-second recomposition is confined to this one text; everything else
 * on the screen renders from the minute-grained [DetailUiState.now]. The
 * ticker runs inside repeatOnLifecycle(STARTED), so it pauses while the app
 * is backgrounded and picks the clock straight back up on return.
 */
@Composable
private fun LiveCountdown(
    nextDoseAt: Instant,
    zone: TimeZone,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableStateOf(Clock.System.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = Clock.System.now()
                delay(1_000)
            }
        }
    }
    Text(
        text = countdownTextSeconds(nextDoseAt, now, zone),
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier,
    )
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
 * Per-drug history: the adherence figure ("N% taken" against the schedule,
 * or a plain take count for as-needed medications), a 16-week heat map —
 * categorical day states (met / partial / not taken / skipped / not
 * tracking) for scheduled medications, raw take counts for as-needed ones —
 * then the latest dose logs interleaved with derived "Not recorded" slots.
 */
@Composable
private fun HistorySection(
    history: List<HistoryEntry>,
    dayStatuses: Map<LocalDate, DoseDayStatus>,
    dayTakeCounts: List<DayCount>,
    adherence: AdherenceResult,
    isAsNeeded: Boolean,
    historyFrom: LocalDate?,
    now: Instant,
    zone: TimeZone,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("History")
            historyFigure(adherence, isAsNeeded)?.let { figure ->
                Text(
                    text = figure,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // The quiet 80%-target verdict under the percent: informational,
        // never alarming (as-needed medications have no target).
        if (!isAsNeeded) {
            adherence.meetsTarget?.let { meets ->
                Text(
                    text = if (meets) "Meets 80% target" else "Below 80% target",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (meets) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
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
            Spacer(Modifier.height(8.dp))
            if (isAsNeeded) {
                // No dose is ever owed: intensity is honest, completeness is not.
                ActivityHeatMap(
                    dayCounts = dayTakeCounts,
                    from = from,
                    today = now.toLocalDate(zone),
                    onDayClick = { date, _ -> onDayClick(date) },
                )
            } else {
                DayStatusHeatMap(
                    dayStatuses = dayStatuses,
                    from = from,
                    today = now.toLocalDate(zone),
                    onDayClick = onDayClick,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        history.forEach { entry ->
            when (entry) {
                is HistoryEntry.Logged -> HistoryRow(log = entry.log, now = now, zone = zone)
                is HistoryEntry.NotRecorded ->
                    NotRecordedRow(slotAt = entry.slotAt, now = now, zone = zone)
            }
        }
    }
}

/** "84% taken" against the schedule; "12 taken" for as-needed medications. */
private fun historyFigure(adherence: AdherenceResult, isAsNeeded: Boolean): String? = when {
    isAsNeeded -> if (adherence.taken == 1) "1 taken" else "${adherence.taken} taken"
    else -> adherence.percent?.let { "$it% taken" }
}

/**
 * 16-week grid of categorical per-day states with its five-swatch legend.
 * Unlike the combined Activity grid (a Less→More intensity ramp), each cell
 * here is one of five answers: met (deep fill), partial (mid fill), not
 * taken (pale fill — calm, not alarming), skipped (dash mark: a choice),
 * or out of treatment (hairline blank).
 */
@Composable
private fun DayStatusHeatMap(
    dayStatuses: Map<LocalDate, DoseDayStatus>,
    from: LocalDate,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ramp = heatRamp()
    val skippedFill = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier) {
        HeatMapGrid(
            from = from,
            today = today,
            cellColor = { date ->
                when (dayStatuses[date]) {
                    DoseDayStatus.MET -> ramp[4]
                    DoseDayStatus.PARTIAL -> ramp[2]
                    DoseDayStatus.NOT_TAKEN -> ramp[1]
                    DoseDayStatus.SKIPPED -> skippedFill
                    // Out-of-treatment days are absent from the map.
                    DoseDayStatus.OUT_OF_TREATMENT, null ->
                        androidx.compose.ui.graphics.Color.Transparent
                }
            },
            cellDash = { date -> dayStatuses[date] == DoseDayStatus.SKIPPED },
            cellHairline = { date ->
                dayStatuses[date] == null ||
                    dayStatuses[date] == DoseDayStatus.OUT_OF_TREATMENT
            },
            cellLabel = { date ->
                "$date: " + when (dayStatuses[date]) {
                    DoseDayStatus.MET -> "all doses taken"
                    DoseDayStatus.PARTIAL -> "some doses taken"
                    DoseDayStatus.NOT_TAKEN -> "doses not taken"
                    DoseDayStatus.SKIPPED -> "skipped by choice"
                    DoseDayStatus.OUT_OF_TREATMENT, null -> "not tracking"
                }
            },
            onDayClick = onDayClick,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LegendChip(color = ramp[4], text = "All taken")
            LegendChip(color = ramp[2], text = "Some")
            LegendChip(color = ramp[1], text = "Not taken")
            LegendChip(color = skippedFill, text = "Skipped", dashed = true)
            LegendChip(
                color = androidx.compose.ui.graphics.Color.Transparent,
                text = "Not tracking",
                hairline = true,
            )
        }
    }
}

@Composable
private fun LegendChip(
    color: androidx.compose.ui.graphics.Color,
    text: String,
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
    hairline: Boolean = false,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        var swatch = Modifier
            .size(10.dp)
            .background(color, MaterialTheme.shapes.extraSmall)
        if (hairline) {
            swatch = swatch.border(
                width = androidx.compose.ui.unit.Dp.Hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.extraSmall,
            )
        }
        Box(swatch, contentAlignment = Alignment.Center) {
            if (dashed) {
                Box(
                    Modifier
                        .size(width = 5.dp, height = 1.5.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
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

/**
 * A derived gap row: an expected slot nothing was logged against. Muted on
 * purpose — it is an absence, not an accusation.
 */
@Composable
private fun NotRecordedRow(
    slotAt: Instant,
    now: Instant,
    zone: TimeZone,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "–",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = dayTimeLabel(slotAt, now, zone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Not recorded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
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
