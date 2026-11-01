package com.example.slicer.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/** A stationary page texture; opaque cards and controls naturally cover the dots. */
@Composable
internal fun Modifier.slicerPageBackground(patternAlpha: () -> Float = { 1f }): Modifier {
    val paper = MaterialTheme.colorScheme.background
    val dotColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
    return background(paper).drawWithCache {
        val spacing = 24.dp.toPx()
        val radius = 1.dp.toPx()
        val dots = Path()
        var y = spacing / 2f
        while (y < size.height) {
            var x = spacing / 2f
            while (x < size.width) {
                dots.addOval(Rect(x - radius, y - radius, x + radius, y + radius))
                x += spacing
            }
            y += spacing
        }
        onDrawBehind {
            // Read the reveal alpha while drawing, keeping the grid fixed during the entrance.
            drawPath(dots, dotColor, alpha = patternAlpha().coerceIn(0f, 1f))
        }
    }
}
