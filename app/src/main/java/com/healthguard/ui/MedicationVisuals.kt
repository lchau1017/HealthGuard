package com.healthguard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.healthguard.ui.theme.categoryTint

/**
 * The list rows' leading avatar: a 44dp rounded square in the medication's
 * category tint with a simple Canvas-drawn capsule glyph — no icon assets.
 */
@Composable
fun PillAvatar(label: String?, modifier: Modifier = Modifier) {
    val tint = categoryTint(label)
    Box(
        modifier = modifier
            .size(44.dp)
            .background(tint.container, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            rotate(degrees = -35f) {
                val width = size.width * 0.92f
                val height = size.height * 0.44f
                val topLeft = Offset((size.width - width) / 2f, (size.height - height) / 2f)
                drawRoundRect(
                    color = tint.content,
                    topLeft = topLeft,
                    size = Size(width, height),
                    cornerRadius = CornerRadius(height / 2f, height / 2f),
                )
                // Capsule seam: a container-coloured gap between the halves.
                drawRect(
                    color = tint.container,
                    topLeft = Offset(size.width / 2f - 1.dp.toPx() / 2f, topLeft.y - 1f),
                    size = Size(1.dp.toPx(), height + 2f),
                )
            }
        }
    }
}

/**
 * Small neutral status chip for a medication's treatment phase: outlined for
 * "Not started" (nothing has happened yet), tonal for "Stopped 3 Jul" (a past
 * fact). Deliberately grey either way — a phase is information, not a warning.
 */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier, outlined: Boolean = false) {
    val shape = RoundedCornerShape(8.dp)
    val base = if (outlined) {
        modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    } else {
        modifier.background(MaterialTheme.colorScheme.surfaceVariant, shape)
    }
    Box(modifier = base.padding(horizontal = 8.dp, vertical = 2.dp)) {
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
            .background(tint.container, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint.content,
            maxLines = 1,
        )
    }
}
