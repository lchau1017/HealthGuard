@file:OptIn(ExperimentalTime::class, ExperimentalLayoutApi::class)

package com.healthguard.detail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.healthguard.activity.AdherenceResult
import com.healthguard.activity.DayCount
import com.healthguard.activity.DoseDayStatus
import com.healthguard.common.format.targetCaption
import com.healthguard.common.theme.Spacing
import com.healthguard.common.theme.heatRamp
import com.healthguard.common.ui.ActivityHeatMap
import com.healthguard.common.ui.HeatMapGrid
import com.healthguard.detail.state.HistoryRowData
import com.healthguard.detail.state.HistoryRowKind
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Per-drug history: the adherence figure ("N% taken" against the schedule,
 * or a plain take count for as-needed medications), a 16-week heat map —
 * categorical day states (met / partial / not taken / skipped / not
 * tracking) for scheduled medications, raw take counts for as-needed ones —
 * then the latest dose logs interleaved with derived "Not recorded" slots.
 */
@Composable
fun HistorySection(
    history: List<HistoryRowData>,
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
                    text = targetCaption(meets),
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
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "No doses recorded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        historyFrom?.let { from ->
            Spacer(Modifier.height(Spacing.sm))
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
        Spacer(Modifier.height(Spacing.sm))
        history.forEach { row ->
            when (row.kind) {
                HistoryRowKind.NOT_RECORDED -> NotRecordedRow(row = row)
                else -> HistoryRow(row = row)
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
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
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
        Spacer(Modifier.width(Spacing.xs))
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
    row: HistoryRowData,
    modifier: Modifier = Modifier,
) {
    val taken = row.kind == HistoryRowKind.TAKEN
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
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = row.annotation,
                style = MaterialTheme.typography.bodySmall,
                color = when (row.kind) {
                    HistoryRowKind.MISSED -> MaterialTheme.colorScheme.error
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
    row: HistoryRowData,
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
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = row.annotation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
