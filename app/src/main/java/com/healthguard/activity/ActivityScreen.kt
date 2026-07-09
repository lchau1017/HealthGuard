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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthguard.home.MedicationPhase
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The Activity tab: window filter, four stat tiles, the record grid at the
 * window's zoom with tap-to-open day-detail sheets, and the per-medicine
 * adherence breakdown — everything recomputes for the selected window.
 * Reloads on entry so takes recorded on other screens are always included.
 */
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onIntent: (ActivityIntent) -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
    LaunchedEffect(Unit) { onIntent(ActivityIntent.Reload) }

    val zone = remember { TimeZone.currentSystemDefault() }
    // "Today" comes from the clock the state was computed against, so the
    // today-outline in the grid rolls over with the content after midnight.
    val today = state.now.toLocalDateTime(zone).date

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
            FilterRow(
                selected = state.filter,
                onSelect = { onIntent(ActivityIntent.SetFilter(it)) },
            )

            if (state.stats.totalEvents == 0) {
                EmptyState()
            } else {
                StatTiles(stats = state.stats)

                state.from?.let { from ->
                    RecordSection(
                        filter = state.filter,
                        dayCounts = state.dayCounts,
                        from = from,
                        today = today,
                        onDayClick = { date, _ -> onIntent(ActivityIntent.SelectDay(date)) },
                    )
                }

                BreakdownList(rows = state.breakdown)
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    state.dayDetail?.let { detail ->
        DayDetailSheet(detail = detail, onDismiss = { onIntent(ActivityIntent.DismissDayDetail) })
    }
}

@Composable
private fun FilterRow(
    selected: ActivityFilter,
    onSelect: (ActivityFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(ActivityFilter.DAYS_7, ActivityFilter.DAYS_30, ActivityFilter.MONTHS_12)
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(windowChipLabel(filter), maxLines = 1) },
            )
        }
    }
}

/**
 * The record grid for the selected window, at that window's natural zoom:
 * a single row of large day cells for 7 days, a handful of week columns for
 * 30 days, and the scrollable GitHub-style year with month labels for 12
 * months. Same data, same Less→More ramp — only the magnification changes.
 */
@Composable
private fun RecordSection(
    filter: ActivityFilter,
    dayCounts: List<DayCount>,
    from: LocalDate,
    today: LocalDate,
    onDayClick: (LocalDate, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = windowHeading(filter),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Each square is one day — darker means more doses taken.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        when (filter) {
            ActivityFilter.DAYS_7 -> DayRowHeatMap(
                dayCounts = dayCounts,
                from = from,
                today = today,
                onDayClick = onDayClick,
            )
            ActivityFilter.DAYS_30 -> ActivityHeatMap(
                dayCounts = dayCounts,
                from = from,
                today = today,
                cellSize = 20.dp,
                cellGap = 3.dp,
                onDayClick = onDayClick,
            )
            ActivityFilter.MONTHS_12 -> ActivityHeatMap(
                dayCounts = dayCounts,
                from = from,
                today = today,
                cellSize = 11.dp,
                cellGap = 2.dp,
                showMonthLabels = true,
                onDayClick = onDayClick,
            )
        }
    }
}

/** Exactly four tiles: doses taken, day streak, active days, usual dose time. */
@Composable
private fun StatTiles(stats: ActivityStats, modifier: Modifier = Modifier) {
    val streakValue =
        if (stats.currentStreakDays == 1) "1 day" else "${stats.currentStreakDays} days"
    data class Tile(val value: String, val label: String, val description: String)
    val tiles = listOf(
        Tile(
            value = "${stats.totalEvents}",
            label = "Doses taken",
            description = "${stats.totalEvents} doses recorded as taken in the selected window",
        ),
        Tile(
            value = streakValue,
            label = "Day streak",
            description = "Day streak: ${stats.currentStreakDays} consecutive days " +
                "with at least one dose",
        ),
        Tile(
            value = "${stats.activeDays}",
            label = "Active days",
            description = "Active days: ${stats.activeDays} days with at least one dose " +
                "in the selected window",
        ),
        Tile(
            value = stats.peakHour?.let(::hourLabel) ?: "—",
            label = "Usual dose time",
            description = stats.peakHour
                ?.let { "Usual dose time: doses are most often taken around ${hourLabel(it)}" }
                ?: "Usual dose time: not enough doses yet",
        ),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowTiles.forEach { tile ->
                    StatTile(
                        label = tile.label,
                        value = tile.value,
                        description = tile.description,
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
    description: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.semantics { contentDescription = description }) {
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
 * "Adherence by medicine": one row per medication with activity in the
 * window — actively scheduled medicines get a percent (against their *own*
 * schedule, spelled out in the caption) plus a thin progress bar with an
 * 80%-target tick; as-needed medicines a bar-less count row; stopped
 * medicines their clipped while-taking figure. Never-started medicines and
 * medicines quiet all window carry no information here and are left out; an
 * entirely quiet list says so in one muted line.
 */
@Composable
private fun BreakdownList(rows: List<MedicationAdherence>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Adherence by medicine",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "How closely each medicine's own schedule was followed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) {
            Text(
                text = "No medicines with activity in this range.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            rows.forEach { row -> BreakdownRow(row) }
        }
    }
}

@Composable
private fun BreakdownRow(row: MedicationAdherence, modifier: Modifier = Modifier) {
    val muted = row.phase != MedicationPhase.TAKING || row.percent == null
    Column(
        modifier = modifier
            .padding(vertical = 6.dp)
            .semantics { contentDescription = adherenceRowDescription(row) },
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (muted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = adherenceRowFigure(row),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        row.percent?.let { percent ->
            Spacer(Modifier.height(4.dp))
            TargetTickedBar(percent = percent)
        }
        if (row.phase == MedicationPhase.TAKING) {
            row.meetsTarget?.let { meets ->
                Spacer(Modifier.height(4.dp))
                TargetCaption(meets = meets)
            }
        }
    }
}

/**
 * The thin adherence bar with a subtle tick at the 80% target so "meets
 * target" is readable from the bar itself, not only from the caption.
 */
@Composable
private fun TargetTickedBar(percent: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
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
        Box(
            Modifier
                .fillMaxWidth(AdherenceResult.TARGET_PERCENT / 100f)
                .height(6.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .width(1.dp)
                    .height(6.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    ),
            )
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
