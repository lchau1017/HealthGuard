package com.healthguard.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import com.healthguard.activity.DAYS_PER_WEEK
import com.healthguard.activity.DayCount
import com.healthguard.activity.mondayOf
import com.healthguard.common.format.shortName
import com.healthguard.common.theme.heatRamp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Maps a day's event count to a color intensity step 0-4. Fixed thresholds
 * (0, 1, 2, 3, 4+) rather than data-relative quantiles: medication counts
 * are small integers and a stable mapping keeps days comparable over time.
 */
internal fun heatLevel(count: Int): Int = count.coerceIn(0, HEAT_LEVELS)

/** The Monday week-column starts spanning [from]..[today], ascending. */
internal fun weekStarts(from: LocalDate, today: LocalDate): List<LocalDate> =
    generateSequence(mondayOf(from)) { it.plus(DAYS_PER_WEEK, DateTimeUnit.DAY) }
        .takeWhile { it <= mondayOf(today) }
        .toList()

/**
 * GitHub-style month labels for a run of [weekStarts] columns: the month's
 * short name at each column whose Monday enters a new month, null elsewhere.
 * A label needs about three columns of room to draw, so months that begin
 * within the last two columns stay unlabeled (the text would clip at the
 * grid's edge), and the first column is labeled only when its month
 * survives the next two columns (a too-narrow leading month would collide
 * with the next label).
 */
internal fun monthLabels(weekStarts: List<LocalDate>): List<String?> =
    weekStarts.mapIndexed { index, monday ->
        val entersNewMonth = index > 0 && monday.month != weekStarts[index - 1].month
        val leadingWithRoom = index == 0 &&
            weekStarts.drop(1).take(2).all { it.month == monday.month }
        val trailingRoom = index <= weekStarts.size - 3
        if ((entersNewMonth || leadingWithRoom) && trailingRoom) {
            monday.month.shortName()
        } else {
            null
        }
    }

/** Single-letter weekday mark under the 7-day row: M T W T F S S. */
internal fun weekdayInitial(date: LocalDate): String = date.dayOfWeek.name.take(1)

private const val HEAT_LEVELS = 4

/**
 * GitHub-style count heat map: the brand's sequential sage ramp over the
 * shared [HeatMapGrid], one intensity step per [heatLevel]. [onDayClick]
 * lets hosts open the tapped day's exact figures in text (the day-detail
 * sheet) so color is never the only way to read a value.
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
    showMonthLabels: Boolean = false,
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
            showMonthLabels = showMonthLabels,
            cellColor = { date -> ramp[heatLevel(countsByDate[date] ?: 0)] },
            cellLabel = { date -> "$date: ${countsByDate[date] ?: 0}" },
            onDayClick = onDayClick?.let { click ->
                { date -> click(date, countsByDate[date] ?: 0) }
            },
        )
        if (showLegend) {
            Spacer(Modifier.size(6.dp))
            HeatLegend(swatchSize = minOf(cellSize, LEGEND_MAX_SWATCH), swatchGap = cellGap)
        }
    }
}

/** Legend swatches never grow past this, whatever the grid's cell size. */
private val LEGEND_MAX_SWATCH = 14.dp

/** The Less→More intensity legend shared by the count-based heat maps. */
@Composable
private fun HeatLegend(swatchSize: Dp, swatchGap: Dp, modifier: Modifier = Modifier) {
    val ramp = heatRamp()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(swatchGap),
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
                    .size(swatchSize)
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

/**
 * The 7-day record: one horizontal row of large day cells, oldest on the
 * left, a weekday initial under each — at a week's zoom the week-column
 * grid would be a single skinny stripe, so the days spread out instead.
 */
@Composable
fun DayRowHeatMap(
    dayCounts: List<DayCount>,
    from: LocalDate,
    today: LocalDate,
    modifier: Modifier = Modifier,
    cellSize: Dp = 36.dp,
    cellGap: Dp = 6.dp,
    onDayClick: ((LocalDate, Int) -> Unit)? = null,
) {
    val countsByDate = remember(dayCounts) { dayCounts.associate { it.date to it.count } }
    val ramp = heatRamp()
    val days = remember(from, today) {
        generateSequence(from) { it.plus(1, DateTimeUnit.DAY) }
            .takeWhile { it <= today }
            .toList()
    }
    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(cellGap)) {
            days.forEach { date ->
                val count = countsByDate[date] ?: 0
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HeatCell(
                        color = ramp[heatLevel(count)],
                        isToday = date == today,
                        size = cellSize,
                        label = "$date: $count",
                        onClick = onDayClick?.let { { it(date, count) } },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = weekdayInitial(date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        HeatLegend(swatchSize = LEGEND_MAX_SWATCH, swatchGap = 2.dp)
    }
}

/**
 * The bare week-column grid shared by every heat map: one column per week
 * (Monday on top), newest week on the right and auto-scrolled into view,
 * today outlined. Hosts decide each day's color ([cellColor]) and
 * accessibility text ([cellLabel]); optionally a day can carry a short
 * horizontal dash mark ([cellDash] — the categorical "skipped by choice"
 * glyph) or a hairline outline ([cellHairline] — out-of-treatment blanks
 * that would otherwise vanish into the background). [showMonthLabels] adds
 * the GitHub-style month strip along the top edge, scrolling with the grid.
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
    showMonthLabels: Boolean = false,
    cellDash: (LocalDate) -> Boolean = { false },
    cellHairline: (LocalDate) -> Boolean = { false },
    onDayClick: ((LocalDate) -> Unit)? = null,
) {
    val weeks = remember(from, today) { weekStarts(from, today) }

    val scrollState = rememberScrollState()
    // Newest week lives at the right edge; land there on first layout
    // and whenever the range grows.
    LaunchedEffect(weeks.size, scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Column(modifier = modifier.horizontalScroll(scrollState)) {
        if (showMonthLabels) {
            val labels = remember(weeks) { monthLabels(weeks) }
            Row(horizontalArrangement = Arrangement.spacedBy(cellGap)) {
                labels.forEach { label ->
                    Box(Modifier.width(cellSize)) {
                        if (label != null) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                // Wider than its 1-cell slot on purpose: the
                                // text hangs over the following columns,
                                // GitHub-style, without stretching the column.
                                modifier = Modifier.wrapContentWidth(
                                    align = Alignment.Start,
                                    unbounded = true,
                                ),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(cellGap)) {
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
                                dashed = cellDash(date),
                                hairline = cellHairline(date),
                                onClick = onDayClick?.let { { it(date) } },
                            )
                        }
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
    dashed: Boolean = false,
    hairline: Boolean = false,
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
    } else if (hairline) {
        cell = cell.border(
            width = Dp.Hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
            shape = MaterialTheme.shapes.extraSmall,
        )
    }
    if (onClick != null) {
        cell = cell.clickable(onClick = onClick)
    }
    Box(cell, contentAlignment = Alignment.Center) {
        if (dashed) {
            Box(
                Modifier
                    .size(width = size / 2, height = 1.5.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}
