package com.healthguard.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.healthguard.activity.DoseDayStatus
import com.healthguard.common.theme.Spacing
import com.healthguard.common.theme.heatRamp
import com.healthguard.common.ui.semanticsLabel
import com.healthguard.common.ui.weekdayInitial
import com.healthguard.home.WeekDay

/**
 * The last seven days as circles — the same categorical day states as the
 * detail heat map: deep fill = met, mid fill = partial, pale fill = not
 * taken, dash mark = skipped by choice, hairline outline = nothing owed;
 * today dashed until decided — with a caption and a link to the full
 * Activity history.
 */
@Composable
internal fun ThisWeekCard(
    weekDays: List<WeekDay>,
    caption: String,
    onOpenActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
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
            Spacer(Modifier.height(Spacing.xs))
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
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = weekdayInitial(day.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
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
            .semanticsLabel(
                when (state) {
                    DoseDayStatus.MET -> "on track"
                    DoseDayStatus.PARTIAL -> "partly on track"
                    DoseDayStatus.NOT_TAKEN -> "doses missed or not recorded"
                    DoseDayStatus.SKIPPED -> "skipped by choice"
                    DoseDayStatus.OUT_OF_TREATMENT -> "nothing scheduled"
                },
            ),
    )
}
