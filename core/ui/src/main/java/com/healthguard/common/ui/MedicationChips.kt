package com.healthguard.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthguard.common.theme.Spacing
import com.healthguard.common.theme.categoryTint

/**
 * Small neutral status chip for a medication's treatment phase: outlined for
 * "Not started" (nothing has happened yet), tonal for "Stopped 3 Jul" (a past
 * fact). Deliberately grey either way — a phase is information, not a warning.
 */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier, outlined: Boolean = false) {
    val shape = MaterialTheme.shapes.small
    val base = if (outlined) {
        modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    } else {
        modifier.background(MaterialTheme.colorScheme.surfaceVariant, shape)
    }
    Box(modifier = base.padding(horizontal = Spacing.sm, vertical = 2.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Small tinted category chip shown under a medication's name. */
@Composable
fun CategoryChip(label: String, modifier: Modifier = Modifier) {
    val tint = categoryTint(label)
    Box(
        modifier = modifier
            .background(tint.container, MaterialTheme.shapes.small)
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint.content,
            maxLines = 1,
        )
    }
}
