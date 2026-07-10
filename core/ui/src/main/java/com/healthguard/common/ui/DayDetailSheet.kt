@file:OptIn(ExperimentalMaterial3Api::class)

package com.healthguard.common.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthguard.activity.DayDetail
import com.healthguard.common.format.dayLineAnnotations
import com.healthguard.common.format.dayLineTitle
import com.healthguard.common.format.expectedNotRecordedText
import com.healthguard.common.format.todayLabel

/**
 * The tapped-day bottom sheet behind every heat-map cell: the day's doses
 * grouped per medicine with exact take times, non-taken outcomes as muted
 * annotation lines, and — when in-treatment schedules expected doses nothing
 * answered — the "expected but not recorded" tally. Replaces the old
 * tap-to-caption mechanism: the whole day is readable as text, not only as
 * color intensity.
 */
@Composable
fun DayDetailSheet(
    detail: DayDetail,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = todayLabel(detail.date),
                style = MaterialTheme.typography.titleLarge,
            )
            if (detail.lines.isEmpty()) {
                Text(
                    text = "No doses recorded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                detail.lines.forEach { line ->
                    Column {
                        Text(
                            text = dayLineTitle(line),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        dayLineAnnotations(line).forEach { annotation ->
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = annotation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (detail.expectedNotRecorded > 0) {
                Text(
                    text = expectedNotRecordedText(detail.expectedNotRecorded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
