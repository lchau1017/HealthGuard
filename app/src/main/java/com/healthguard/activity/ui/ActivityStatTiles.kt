package com.healthguard.activity.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthguard.activity.ActivityStats
import com.healthguard.activity.format.statTiles
import com.healthguard.common.theme.Spacing
import com.healthguard.common.ui.semanticsLabel

/** Exactly four tiles: doses taken, day streak, active days, usual dose time. */
@Composable
internal fun StatTiles(stats: ActivityStats, modifier: Modifier = Modifier) {
    // The tiles are pure string formatting; recompute only when the stats change.
    val tiles = remember(stats) { statTiles(stats) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
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
    Card(modifier = modifier.semanticsLabel(description)) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
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
