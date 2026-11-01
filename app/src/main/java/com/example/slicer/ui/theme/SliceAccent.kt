package com.example.slicer.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The same diagonal cut is used at the progress edge and on the savings panel. */
private fun slicePath(right: Float, top: Float, height: Float, width: Float): Path {
    val slant = height * 0.55f
    return Path().apply {
        moveTo(right - width, top)
        lineTo(right, top)
        lineTo(right - slant, top + height)
        lineTo(right - width - slant, top + height)
        close()
    }
}

@Composable
internal fun SliceProgressIndicator(progress: Float, modifier: Modifier = Modifier) {
    val value = progress.coerceIn(0f, 1f)
    val colors = MaterialTheme.colorScheme
    // Glide between per-image steps instead of jumping, and keep a sheen moving so the bar never looks frozen.
    val shown = animateFloatAsState(value, tween(600), label = "slice-progress")
    val sheen = rememberInfiniteTransition(label = "slice-sheen").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "slice-sheen-position"
    )
    Canvas(
        modifier = modifier.height(12.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) }
            .clip(MaterialTheme.shapes.small)
    ) {
        drawRect(colors.surfaceVariant)
        val edge = size.width * shown.value
        // Clip the signature to the completed portion: zero progress must remain empty.
        clipRect(right = edge) {
            drawRect(colors.primary)
            val band = 64.dp.toPx()
            val x = -band + (edge + band) * sheen.value
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                    startX = x,
                    endX = x + band
                )
            )
            drawPath(slicePath(edge, 0f, size.height, 10.dp.toPx()), colors.background)
            drawPath(slicePath(edge, 0f, size.height, 7.dp.toPx()), colors.secondary)
        }
    }
}

/** A small cut at the top end; callers reserve space above the panel's text. */
@Composable
internal fun Modifier.savingsSlice(): Modifier {
    val colors = MaterialTheme.colorScheme
    return clip(MaterialTheme.shapes.medium).drawWithCache {
        val height = 32.dp.toPx()
        val cut = slicePath(size.width, 0f, height, 18.dp.toPx())
        val accent = slicePath(size.width, 0f, height, 12.dp.toPx())
        onDrawBehind {
            drawRect(colors.surface)
            drawPath(cut, colors.background)
            drawPath(accent, colors.secondary)
        }
    }
}

private class Shard(
    val angle: Float,
    val speed: Float,
    val spin: Float,
    val length: Float,
    val colorIndex: Int,
    val delay: Float
)

/**
 * A one-off burst of little slices, thrown up from the middle of the area and falling back with
 * gravity. Everything happens in the draw phase, so the burst never recomposes the screen.
 */
@Composable
internal fun SliceBurst(play: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val palette = listOf(colors.primary, colors.secondary, colors.onSurface)
    val shards = remember {
        val random = Random(7)
        List(36) {
            Shard(
                // Mostly upwards, fanned out to both sides.
                angle = Math.toRadians(-90.0 + (random.nextFloat() - 0.5f) * 150.0).toFloat(),
                speed = 0.55f + random.nextFloat() * 0.75f,
                spin = (random.nextFloat() - 0.5f) * 900f,
                length = 8f + random.nextFloat() * 10f,
                colorIndex = it % 3,
                delay = random.nextFloat() * 0.12f
            )
        }
    }
    val time = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(play) {
        if (play) {
            time.snapTo(0f)
            time.animateTo(1f, tween(1700, easing = LinearEasing))
        }
    }
    Canvas(modifier = modifier) {
        val t = time.value
        if (t >= 1f) return@Canvas
        val origin = Offset(size.width / 2f, size.height * 0.42f)
        val reach = size.height * 1.5f
        val gravity = reach * 2.2f
        shards.forEach { shard ->
            val local = ((t - shard.delay) / (1f - shard.delay)).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            val vx = cos(shard.angle) * shard.speed * reach
            val vy = sin(shard.angle) * shard.speed * reach
            val x = origin.x + vx * local
            val y = origin.y + vy * local + 0.5f * gravity * local * local
            val alpha = if (local < 0.6f) 1f else (1f - (local - 0.6f) / 0.4f)
            val w = shard.length.dp.toPx()
            val h = w * 0.45f
            translate(x, y) {
                rotate(shard.spin * local, pivot = Offset.Zero) {
                    drawPath(slicePath(w / 2f, -h / 2f, h, w), palette[shard.colorIndex], alpha = alpha.coerceIn(0f, 1f))
                }
            }
        }
    }
}
