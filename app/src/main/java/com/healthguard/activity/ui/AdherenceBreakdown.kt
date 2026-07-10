package com.healthguard.activity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthguard.activity.AdherenceResult
import com.healthguard.activity.format.adherenceRowDescription
import com.healthguard.activity.format.adherenceRowFigure
import com.healthguard.activity.state.MedicationAdherence
import com.healthguard.common.format.targetCaption
import com.healthguard.common.theme.Spacing
import com.healthguard.common.ui.semanticsLabel
import com.healthguard.home.MedicationPhase

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
internal fun BreakdownList(rows: List<MedicationAdherence>, modifier: Modifier = Modifier) {
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
        Spacer(Modifier.height(Spacing.sm))
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
            .semanticsLabel(adherenceRowDescription(row)),
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
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = adherenceRowFigure(row),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        row.percent?.let { percent ->
            Spacer(Modifier.height(Spacing.xs))
            TargetTickedBar(percent = percent)
        }
        if (row.phase == MedicationPhase.TAKING) {
            row.meetsTarget?.let { meets ->
                Spacer(Modifier.height(Spacing.xs))
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
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            text = targetCaption(meets),
            style = MaterialTheme.typography.labelSmall,
            color = if (meets) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
