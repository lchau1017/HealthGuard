@file:OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)

package com.healthguard.activity

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The Activity tab: window filter, four stat tiles, the fixed 16-week heat
 * map with a tap-to-read caption, and the per-medicine adherence breakdown.
 * Reloads on entry so takes recorded on other screens are always included.
 */
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.reload() }

    // Tapped heat-map day, shown as a caption so the value is readable as
    // text, not only as color intensity.
    var selectedDay by remember { mutableStateOf<Pair<LocalDate, Int>?>(null) }

    val zone = remember { TimeZone.currentSystemDefault() }
    val today = remember(state) { Clock.System.now().toLocalDateTime(zone).date }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Activity") })
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FilterRow(selected = state.filter, onSelect = viewModel::setFilter)

            if (state.stats.totalEvents == 0) {
                EmptyState()
            } else {
                StatTiles(stats = state.stats)

                state.heatFrom?.let { from ->
                    Column {
                        Text(
                            text = "16-week record",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        ActivityHeatMap(
                            dayCounts = state.dayCounts,
                            from = from,
                            today = today,
                            cellSize = 18.dp,
                            cellGap = 3.dp,
                            onDayClick = { date, count -> selectedDay = date to count },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = selectedDay?.let { (date, count) ->
                                val doses = if (count == 1) "1 dose" else "$count doses"
                                "${dayLabel(date)} — $doses"
                            } ?: "Tap a day for its count",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                BreakdownList(rows = state.breakdown)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun FilterRow(
    selected: ActivityFilter,
    onSelect: (ActivityFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        "All time" to ActivityFilter.ALL,
        "30 days" to ActivityFilter.DAYS_30,
        "7 days" to ActivityFilter.DAYS_7,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (label, filter) ->
            SegmentedButton(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** Exactly four tiles: doses taken, current run, active days, most consistent. */
@Composable
private fun StatTiles(stats: ActivityStats, modifier: Modifier = Modifier) {
    val runValue = if (stats.currentStreakDays == 1) "1 day" else "${stats.currentStreakDays} days"
    val tiles = listOf(
        "${stats.totalEvents}" to "Doses taken",
        runValue to "Current run",
        "${stats.activeDays}" to "Active days",
        (stats.peakHour?.let(::hourLabel) ?: "—") to "Most consistent",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowTiles.forEach { (value, label) ->
                    StatTile(
                        label = label,
                        value = value,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Per-medicine adherence against each schedule. Scheduled medicines get a
 * percent plus a thin progress bar (skips noted beside the figure);
 * as-needed (interval) medicines get a bar-less "As needed · N taken" row —
 * a percent would presume doses that were never mandatory.
 */
@Composable
private fun BreakdownList(rows: List<MedicationAdherence>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "By medicine",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        rows.forEach { row ->
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = breakdownFigure(row),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                row.percent?.let { percent ->
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.shapes.extraSmall,
                            ),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(percent / 100f)
                                .height(6.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.shapes.extraSmall,
                                ),
                        )
                    }
                }
                row.meetsTarget?.let { meets ->
                    Spacer(Modifier.height(4.dp))
                    TargetCaption(meets = meets)
                }
            }
        }
    }
}

/**
 * The quiet 80%-target line under a scheduled row: a filled check-dot when
 * the percent reaches the clinical threshold, plain informational text when
 * it does not — never error-red; falling short is a conversation with a
 * pharmacist, not an alarm.
 */
@Composable
private fun TargetCaption(meets: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (meets) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✓",
                    fontSize = 8.sp,
                    lineHeight = 8.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = if (meets) "Meets 80% target" else "Below 80% target",
            style = MaterialTheme.typography.labelSmall,
            color = if (meets) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** "84%", "84% · 2 skipped", "As needed · 5 taken", or a bare "5 taken". */
private fun breakdownFigure(row: MedicationAdherence): String {
    val takenText = if (row.taken == 1) "1 taken" else "${row.taken} taken"
    return when {
        row.asNeeded -> "As needed · $takenText"
        row.percent == null -> takenText
        row.skipped > 0 -> "${row.percent}% · ${row.skipped} skipped"
        else -> "${row.percent}%"
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 96.dp),
    ) {
        Text(
            text = "No doses recorded yet",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Take a dose from the home screen and your history will build up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
