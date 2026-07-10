@file:OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)

package com.healthguard.detail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.healthguard.common.theme.Spacing
import com.healthguard.common.ui.CategoryChip
import com.healthguard.common.ui.DayDetailSheet
import com.healthguard.common.ui.DoubleDoseDialog
import com.healthguard.common.ui.StatusChip
import com.healthguard.common.ui.showUndoTakeSnackbar
import com.healthguard.detail.format.countdownTextSeconds
import com.healthguard.detail.format.lastTakenLabel
import com.healthguard.detail.state.DetailEffect
import com.healthguard.detail.state.DetailFinished
import com.healthguard.detail.state.DetailIntent
import com.healthguard.detail.state.DetailUiState
import com.healthguard.home.MedicationPhase
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
                    is DetailEffect.ShowUndoSnackbar ->
                        if (snackbarHostState.showUndoTakeSnackbar(effect.take)) {
                            onIntent(DetailIntent.UndoTake(effect.take.doseId))
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
                .padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            HeaderBlock(
                drugName = state.drugName,
                subtitle = state.subtitle,
                categoryLabel = state.categoryLabel,
                phaseChipText = state.phaseChipText,
                phase = state.phase,
            )

            if (state.isActive) {
                StatusCard(
                    nextDoseAt = state.nextDoseAt,
                    lastTakenAt = state.lastTakenAt,
                    now = state.now,
                    zone = zone,
                    onTakeNow = { onIntent(DetailIntent.TakeNow) },
                )
            }

            ScheduleCard(
                timesText = state.scheduleTimesText,
                startedText = state.scheduleStartedText,
            )

            DetailForm(state = state.formState, onIntent = onIntent)

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

            Spacer(Modifier.height(Spacing.sm))
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
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    state.takeConfirm?.let { minutesAgo ->
        DoubleDoseDialog(
            drugName = state.drugName ?: "this medication",
            minutesAgo = minutesAgo,
            onConfirm = { onIntent(DetailIntent.ConfirmTakeAnyway) },
            onDismiss = { onIntent(DetailIntent.DismissTakeConfirm) },
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
            medicationName = state.drugName ?: "this medication",
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
    drugName: String?,
    subtitle: String,
    categoryLabel: String?,
    phaseChipText: String?,
    phase: MedicationPhase,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = drugName ?: "Medication",
            style = MaterialTheme.typography.headlineMedium,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (categoryLabel != null || phaseChipText != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                categoryLabel?.let { label ->
                    CategoryChip(label)
                    Spacer(Modifier.width(6.dp))
                }
                phaseChipText?.let { text ->
                    StatusChip(
                        text = text,
                        outlined = phase == MedicationPhase.NOT_STARTED,
                    )
                }
            }
        }
    }
}

/** Live dose status: countdown, last take, and the guarded Take now action. */
@Composable
private fun StatusCard(
    nextDoseAt: Instant?,
    lastTakenAt: Instant?,
    now: Instant,
    zone: TimeZone,
    onTakeNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            if (nextDoseAt != null) {
                Text(
                    text = "Next dose",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LiveCountdown(nextDoseAt = nextDoseAt, zone = zone)
            }
            lastTakenAt?.let { lastTaken ->
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = lastTakenLabel(lastTaken, now, zone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.md))
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
    timesText: String?,
    startedText: String?,
    modifier: Modifier = Modifier,
) {
    if (timesText == null && startedText == null) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
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

/** The primary-tinted section heading shared by the form and history blocks. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = Spacing.sm),
    )
}

internal fun Instant.toLocalDate(zone: TimeZone): LocalDate = toLocalDateTime(zone).date
