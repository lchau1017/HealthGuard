package com.healthguard.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.healthguard.ui.theme.heatRamp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Maps a day's event count to a color intensity step 0-4. Fixed thresholds
 * (0, 1, 2, 3, 4+) rather than data-relative quantiles: medication counts
 * are small integers and a stable mapping keeps days comparable over time.
 */
fun heatLevel(count: Int): Int = count.coerceIn(0, HEAT_LEVELS)

/** The Monday on or before [date]. */
fun mondayOf(date: LocalDate): LocalDate =
    date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

private const val HEAT_LEVELS = 4
private const val DAYS_PER_WEEK = 7

/**
 * GitHub-style count heat map: the brand's sequential sage ramp over the
 * shared [HeatMapGrid], one intensity step per [heatLevel]. [onDayClick]
 * lets hosts surface the tapped day's exact figure in text so color is
 * never the only way to read a value.
 */
@Composable
fun ActivityHeatMap(
    dayCounts: List<DayCount>,
    from: LocalDate,
    today: LocalDate,
    modifier: Modifier = Modifier,
    cellSize: Dp = 14.dp,
    cellGap: Dp = 2.dp,
    showLegend: Boolean = true,
    onDayClick: ((LocalDate, Int) -> Unit)? = null,
) {
    val countsByDate = remember(dayCounts) { dayCounts.associate { it.date to it.count } }
    val ramp = heatRamp()

    Column(modifier = modifier) {
        HeatMapGrid(
            from = from,
            today = today,
            cellSize = cellSize,
            cellGap = cellGap,
            cellColor = { date -> ramp[heatLevel(countsByDate[date] ?: 0)] },
            cellLabel = { date -> "$date: ${countsByDate[date] ?: 0}" },
            onDayClick = onDayClick?.let { click ->
                { date -> click(date, countsByDate[date] ?: 0) }
            },
        )
        if (showLegend) {
            Spacer(Modifier.size(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(cellGap),
            ) {
                Text(
                    text = "Less",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(2.dp))
                ramp.forEach { color ->
                    Box(
                        Modifier
                            .size(cellSize)
                            .background(color, MaterialTheme.shapes.extraSmall),
                    )
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = "More",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The bare week-column grid shared by every heat map: one column per week
 * (Monday on top), newest week on the right and auto-scrolled into view,
 * today outlined. Hosts decide each day's color ([cellColor]) and
 * accessibility text ([cellLabel]).
 */
@Composable
fun HeatMapGrid(
    from: LocalDate,
    today: LocalDate,
    cellColor: (LocalDate) -> Color,
    cellLabel: (LocalDate) -> String,
    modifier: Modifier = Modifier,
    cellSize: Dp = 14.dp,
    cellGap: Dp = 2.dp,
    onDayClick: ((LocalDate) -> Unit)? = null,
) {
    val firstMonday = mondayOf(from)
    val lastMonday = mondayOf(today)
    val weeks = remember(firstMonday, lastMonday) {
        generateSequence(firstMonday) { it.plus(DAYS_PER_WEEK, DateTimeUnit.DAY) }
            .takeWhile { it <= lastMonday }
            .toList()
    }

    val scrollState = rememberScrollState()
    // Newest week lives at the right edge; land there on first layout
    // and whenever the range grows.
    LaunchedEffect(weeks.size, scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Row(
        modifier = modifier.horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(cellGap),
    ) {
        weeks.forEach { weekStart ->
            Column(verticalArrangement = Arrangement.spacedBy(cellGap)) {
                repeat(DAYS_PER_WEEK) { dayIndex ->
                    val date = weekStart.plus(dayIndex, DateTimeUnit.DAY)
                    if (date < from || date > today) {
                        Spacer(Modifier.size(cellSize))
                    } else {
                        HeatCell(
                            color = cellColor(date),
                            isToday = date == today,
                            size = cellSize,
                            label = cellLabel(date),
                            onClick = onDayClick?.let { { it(date) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatCell(
    color: Color,
    isToday: Boolean,
    size: Dp,
    label: String,
    onClick: (() -> Unit)?,
) {
    var cell = Modifier
        .size(size)
        .background(color, MaterialTheme.shapes.extraSmall)
        .semantics { contentDescription = label }
    if (isToday) {
        cell = cell.border(
            width = 1.5.dp,
            color = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraSmall,
        )
    }
    if (onClick != null) {
        cell = cell.clickable(onClick = onClick)
    }
    Box(cell)
}
