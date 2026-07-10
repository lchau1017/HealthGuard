@file:OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)

package com.healthguard.home.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.healthguard.common.theme.Spacing
import com.healthguard.common.ui.semanticsLabel
import com.healthguard.home.format.takeByText
import com.healthguard.home.state.DueAlert
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Shown only while something is due: a surface card with an error-tinted
 * border, the most urgent item's "take by" line and the guarded Take action,
 * plus a count of any other due items.
 */
@Composable
internal fun DueAlertCard(
    alert: DueAlert,
    now: Instant,
    zone: TimeZone,
    onTakeNow: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = alert.card
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .semanticsLabel("Dose due: ${card.drugName}, open details"),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DueBadge()
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = card.title + " is due",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    card.nextDoseAt?.let { nextDoseAt ->
                        Text(
                            text = takeByText(nextDoseAt, now, zone),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (alert.othersDueCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "and ${alert.othersDueCount} more due now",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Button(
                onClick = onTakeNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .semanticsLabel("Take ${card.drugName} now"),
            ) {
                Text("Take now", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Circular "!" badge on the due card. */
@Composable
private fun DueBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(36.dp)
            .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "!",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
