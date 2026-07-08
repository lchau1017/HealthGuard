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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The Activity dashboard: window filter, stat tiles, the full heat map with a
 * tap-to-read caption, and the per-medication breakdown. Reloads on entry so
 * takes recorded on other screens are always included.
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

                state.from?.let { from ->
                    Column {
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
        "All" to ActivityFilter.ALL,
        "30d" to ActivityFilter.DAYS_30,
        "7d" to ActivityFilter.DAYS_7,
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

@Composable
private fun StatTiles(stats: ActivityStats, modifier: Modifier = Modifier) {
    val tiles = listOf(
        "Doses taken" to "${stats.totalEvents}",
        "Current streak" to "${stats.currentStreakDays}d",
        "Longest streak" to "${stats.longestStreakDays}d",
        "Active days" to "${stats.activeDays}",
        "Peak hour" to (stats.peakHour?.let(::hourLabel) ?: "—"),
        "Most taken" to (stats.topItem?.name ?: "—"),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowTiles.forEach { (label, value) ->
                    StatTile(
                        label = label,
                        value = value,
                        // Drug names don't fit display type; step down for text values.
                        compactValue = label == "Most taken",
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
    compactValue: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = value,
                style = if (compactValue) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.headlineMedium
                },
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

@Composable
private fun BreakdownList(rows: List<MedicationBreakdown>, modifier: Modifier = Modifier) {
    val maxCount = rows.maxOfOrNull { it.count } ?: return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "By medication",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        rows.forEach { row ->
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "${row.count} · ${row.percent}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Thin single-hue proportion bar, scaled to the largest row.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.extraSmall,
                        ),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(row.count.toFloat() / maxCount)
                            .height(6.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.shapes.extraSmall,
                            ),
                    )
                }
            }
        }
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
