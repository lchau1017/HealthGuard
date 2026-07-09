package com.healthguard.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.healthguard.common.theme.categoryTint

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
