@file:OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)

package com.healthguard.activity.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.healthguard.activity.ActivityFilter
import com.healthguard.activity.DayCount
import com.healthguard.activity.format.windowChipLabel
import com.healthguard.activity.format.windowHeading
import com.healthguard.activity.state.ActivityIntent
import com.healthguard.activity.state.ActivityUiState
import com.healthguard.common.theme.Spacing
import com.healthguard.common.ui.ActivityHeatMap
import com.healthguard.common.ui.DayDetailSheet
import com.healthguard.common.ui.DayRowHeatMap
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The Activity tab: window filter, four stat tiles, the record grid at the
 * window's zoom with tap-to-open day-detail sheets, and the per-medicine
 * adherence breakdown — everything recomputes for the selected window.
 * Freshness is the view model's job: it loads on construction and re-queries
 * on every repository data change, so takes recorded on other screens are
 * always included without the host raising anything on entry.
 */
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onIntent: (ActivityIntent) -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
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
                .padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
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
            Spacer(Modifier.height(Spacing.xl))
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
        Spacer(Modifier.height(Spacing.sm))
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
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "Take a dose from the home screen and your history will build up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
