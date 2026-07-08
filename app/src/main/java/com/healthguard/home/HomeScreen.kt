@file:OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)

package com.healthguard.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.healthguard.BuildConfig
import com.healthguard.activity.DoseDayStatus
import com.healthguard.dose.RecordedTake
import com.healthguard.format.DoseRowStatus
import com.healthguard.format.takeByText
import com.healthguard.format.todayLabel
import com.healthguard.shared.data.MedicationWithSchedule
import com.healthguard.ui.CategoryChip
import com.healthguard.ui.PillAvatar
import com.healthguard.ui.theme.heatRamp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
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
    takeConfirm: TakeConfirmation?,
    recentTake: RecordedTake?,
    onTakeNow: (DoseCard) -> Unit,
    onConfirmTakeAnyway: () -> Unit,
    onDismissTakeConfirm: () -> Unit,
    onUndoTake: (String) -> Unit,
    onRecentTakeHandled: () -> Unit,
    onPlay: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenActivity: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    onLoadDemoData: () -> Unit = {},
    onRemoveDemoData: () -> Unit = {},
) {
    var showSourceSheet by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Fresh wall-clock reading per state emission: the view model re-emits at
    // least every minute, which is exactly the countdown's resolution.
    val now = remember(state) { Clock.System.now() }
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = now.toLocalDateTime(zone).date

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
                    // Demo data controls exist in debug builds only.
                    if (BuildConfig.DEBUG) {
                        var demoMenuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { demoMenuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "Developer options",
                            )
                        }
                        DropdownMenu(
                            expanded = demoMenuOpen,
                            onDismissRequest = { demoMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Load demo data") },
                                onClick = {
                                    demoMenuOpen = false
                                    onLoadDemoData()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Remove demo data") },
                                onClick = {
                                    demoMenuOpen = false
                                    onRemoveDemoData()
                                },
                            )
                        }
                    }
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
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    Spacer(Modifier.height(4.dp))
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
                        onTakeNow = { onTakeNow(alert.card) },
                        onClick = { onOpenDetail(alert.card.item.medication.id) },
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
                    items(state.taking, key = { "taking-${it.item.medication.id}" }) { card ->
                        TakingRow(
                            card = card,
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

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text("HealthGuard is an information tool") },
            text = {
                Text(
                    "HealthGuard helps you keep track of your medications. It is " +
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

/**
 * Shown only while something is due: a surface card with an error-tinted
 * border, the most urgent item's "take by" line and the guarded Take action,
 * plus a count of any other due items.
 */
@Composable
private fun DueAlertCard(
    alert: DueAlert,
    now: Instant,
    zone: TimeZone,
    onTakeNow: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = alert.card
    val medication = card.item.medication
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .semanticsLabel("Dose due: ${medication.drugName}, open details"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DueBadge()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = listOfNotNull(medication.drugName, medication.dosage)
                            .joinToString(" ") + " is due",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    card.nextDoseAt?.let { nextDoseAt ->
                        Text(
                            text = takeByText(nextDoseAt, now, zone),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (alert.othersDueCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "and ${alert.othersDueCount} more due now",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
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

/** Circular "!" badge on the due card. */
@Composable
private fun DueBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(36.dp)
            .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "!",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * The last seven days as circles — the same categorical day states as the
 * detail heat map: deep fill = met, mid fill = partial, pale fill = not
 * taken, dash mark = skipped by choice, hairline outline = nothing owed;
 * today dashed until decided — with a caption and a link to the full
 * Activity history.
 */
@Composable
private fun ThisWeekCard(
    weekDays: List<WeekDay>,
    caption: String,
    onOpenActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "This week",
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(
                    onClick = onOpenActivity,
                    modifier = Modifier.semanticsLabel("Open full activity history"),
                ) {
                    Text("Full history →")
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                weekDays.forEachIndexed { index, day ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        WeekCircle(
                            state = day.state,
                            isToday = index == weekDays.lastIndex,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = day.date.dayOfWeek.name.take(1),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeekCircle(
    state: DoseDayStatus,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    val ramp = heatRamp()
    val fill = when (state) {
        DoseDayStatus.MET -> MaterialTheme.colorScheme.primary
        DoseDayStatus.PARTIAL -> ramp[2]
        DoseDayStatus.NOT_TAKEN -> ramp[1]
        DoseDayStatus.SKIPPED -> MaterialTheme.colorScheme.surfaceVariant
        DoseDayStatus.OUT_OF_TREATMENT -> Color.Transparent
    }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val todayOutline = MaterialTheme.colorScheme.primary
    val dashMark = MaterialTheme.colorScheme.onSurfaceVariant
    // Today stays dashed until it is decided fully on-track (then it fills).
    val dashed = isToday && state != DoseDayStatus.MET
    Box(
        modifier = modifier
            .size(28.dp)
            .drawBehind {
                if (fill != Color.Transparent) drawCircle(color = fill)
                when {
                    dashed -> drawCircle(
                        color = todayOutline,
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        ),
                    )
                    fill == Color.Transparent -> drawCircle(
                        color = outline,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                // The categorical "skipped by choice" glyph: a short
                // horizontal dash, matching the detail heat map.
                if (state == DoseDayStatus.SKIPPED) {
                    val half = size.width / 4f
                    drawLine(
                        color = dashMark,
                        start = Offset(center.x - half, center.y),
                        end = Offset(center.x + half, center.y),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            }
            .semantics {
                contentDescription = when (state) {
                    DoseDayStatus.MET -> "on track"
                    DoseDayStatus.PARTIAL -> "partly on track"
                    DoseDayStatus.NOT_TAKEN -> "doses missed or not recorded"
                    DoseDayStatus.SKIPPED -> "skipped by choice"
                    DoseDayStatus.OUT_OF_TREATMENT -> "nothing scheduled"
                }
            },
    )
}

/** One "Taking now" row: category avatar, name, chip + form, trailing status. */
@Composable
private fun TakingRow(
    card: DoseCard,
    onTakeNow: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val medication = card.item.medication
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semanticsLabel("${medication.drugName}, open details"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillAvatar(label = medication.label)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOfNotNull(medication.drugName, medication.dosage)
                        .joinToString(" "),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    medication.label?.let { label ->
                        CategoryChip(label)
                        Spacer(Modifier.width(6.dp))
                    }
                    medication.form?.let { form ->
                        Text(
                            text = form.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            when (val status = card.status) {
                DoseRowStatus.Due -> FilledTonalButton(
                    onClick = onTakeNow,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semanticsLabel("Take ${medication.drugName} now"),
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

/** Cabinet rows share the taking-row look; the play affordance starts tracking. */
@Composable
private fun CabinetRow(
    row: MedicationWithSchedule,
    onPlay: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val medication = row.medication
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semanticsLabel("${medication.drugName}, open details"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillAvatar(label = medication.label)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOfNotNull(medication.drugName, medication.dosage)
                        .joinToString(" "),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    medication.label?.let { label ->
                        CategoryChip(label)
                        Spacer(Modifier.width(6.dp))
                    }
                    medication.form?.let { form ->
                        Text(
                            text = form.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
