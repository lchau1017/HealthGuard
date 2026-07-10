@file:OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)

package com.healthguard.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.healthguard.common.format.todayLabel
import com.healthguard.common.theme.Spacing
import com.healthguard.common.ui.DoubleDoseDialog
import com.healthguard.common.ui.semanticsLabel
import com.healthguard.common.ui.showUndoTakeSnackbar
import com.healthguard.home.state.HomeEffect
import com.healthguard.home.state.HomeIntent
import com.healthguard.home.state.HomeUiState
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The home tab: a "Today" header, the due-dose alert card, the "This week"
 * circles, the active "Taking now" list and the dormant "My cabinet" below,
 * with the scan flow on an extended FAB. Every card and row opens the
 * medication detail page; deleting lives there too.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onIntent: (HomeIntent) -> Unit,
    effects: Flow<HomeEffect>,
    onOpenDetail: (String) -> Unit,
    onOpenActivity: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
    // Saveable so rotation (or process death) keeps an open sheet/dialog open.
    var showSourceSheet by rememberSaveable { mutableStateOf(false) }
    var showDisclaimer by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // The wall clock the state was computed against: the view model re-emits
    // at least every minute with a fresh `now`, so clock-derived text can
    // never drift from (or outlive) the computed facts beside it.
    val now = state.now
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = now.toLocalDateTime(zone).date

    // One undo snackbar per recorded take; timing out or dismissing keeps
    // the dose, only the explicit Undo action removes it. Collected only
    // while STARTED; the Channel-backed effects are buffered, so anything
    // emitted while STOPPED is delivered when collection resumes.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(effects, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            effects.collect { effect ->
                when (effect) {
                    is HomeEffect.ShowUndoSnackbar ->
                        if (snackbarHostState.showUndoTakeSnackbar(effect.take)) {
                            onIntent(HomeIntent.UndoTake(effect.take.doseId))
                        }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text("HealthGuard", style = MaterialTheme.typography.titleMedium)
                },
                actions = {
                    IconButton(onClick = { showDisclaimer = true }) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "About HealthGuard and medical disclaimer",
                        )
                    }
                    DemoDataMenu(onIntent)
                },
            )
        },
        bottomBar = bottomBar,
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
                .padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "today-header") {
                Column {
                    Text(
                        text = "Today",
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = todayLabel(today),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = "For your records only — always consult your " +
                            "doctor or pharmacist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.dueAlert?.let { alert ->
                item(key = "due-alert") {
                    DueAlertCard(
                        alert = alert,
                        now = now,
                        zone = zone,
                        onTakeNow = { onIntent(HomeIntent.TakeNow(alert.card.scheduleId)) },
                        onClick = { onOpenDetail(alert.card.medicationId) },
                    )
                }
            }

            if (state.taking.isEmpty() && state.cabinet.isEmpty()) {
                item(key = "empty-all") { OverallEmptyState() }
            } else {
                item(key = "this-week") {
                    ThisWeekCard(
                        weekDays = state.weekDays,
                        caption = state.weekCaption,
                        onOpenActivity = onOpenActivity,
                    )
                }
                item(key = "taking-header") { SectionHeader("Taking now") }
                if (state.taking.isEmpty()) {
                    item(key = "taking-empty") {
                        SectionEmptyText(
                            "Nothing active yet — press play on a cabinet " +
                                "medication to start tracking doses.",
                        )
                    }
                } else {
                    items(state.taking, key = { "taking-${it.medicationId}" }) { card ->
                        TakingRow(
                            card = card,
                            onTakeNow = { onIntent(HomeIntent.TakeNow(card.scheduleId)) },
                            onClick = { onOpenDetail(card.medicationId) },
                        )
                    }
                }

                item(key = "cabinet-header") { SectionHeader("My cabinet") }
                if (state.cabinet.isEmpty()) {
                    item(key = "cabinet-empty") {
                        SectionEmptyText("Everything you scanned is in use.")
                    }
                } else {
                    items(state.cabinet, key = { "cabinet-${it.medicationId}" }) { row ->
                        CabinetRowCard(
                            row = row,
                            onPlay = { onIntent(HomeIntent.Play(row.medicationId)) },
                            onClick = { onOpenDetail(row.medicationId) },
                        )
                    }
                }
            }
            item(key = "fab-spacer") { Spacer(Modifier.height(72.dp)) }
        }
    }

    state.takeConfirm?.let { confirm ->
        DoubleDoseDialog(
            drugName = confirm.card.drugName,
            minutesAgo = confirm.minutesAgo,
            onConfirm = { onIntent(HomeIntent.ConfirmTakeAnyway) },
            onDismiss = { onIntent(HomeIntent.DismissTakeConfirm) },
        )
    }

    if (showDisclaimer) {
        DisclaimerDialog(onDismiss = { showDisclaimer = false })
    }

    if (showSourceSheet) {
        PhotoSourceSheet(
            onDismiss = { showSourceSheet = false },
            onTakePhoto = onTakePhoto,
            onPickFromGallery = onPickFromGallery,
        )
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = Spacing.sm),
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
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "Take a photo of a medication label and it will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
