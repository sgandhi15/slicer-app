package com.example.slicer.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import com.example.slicer.ui.theme.SlicerButton as Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

private val PREVIEW_MIN_HEIGHT = 320.dp
// Wide photos get a shorter panel so the divider and labels sit on the image, not in empty bands.
private val PREVIEW_MIN_FITTED_HEIGHT = 160.dp
private val PREVIEW_MAX_HEIGHT = 560.dp

@Composable
internal fun CompareScreen(
    beforeImage: ImageBitmap?,
    afterImage: ImageBitmap?,
    isGeneratingComparison: Boolean,
    onBack: () -> Unit,
    controlsEnabled: Boolean,
    notice: String?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Original vs compressed",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            BeforeAfterPanel(
                beforeImage = beforeImage,
                afterImage = afterImage,
                isLoading = isGeneratingComparison,
                controlsEnabled = controlsEnabled
            )

            if (notice != null) {
                Spacer(modifier = Modifier.height(10.dp))
                NoticeText(message = notice)
            }

        }

        Button(
            onClick = onBack,
            enabled = controlsEnabled,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(52.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun BeforeAfterPanel(
    beforeImage: ImageBitmap?,
    afterImage: ImageBitmap?,
    isLoading: Boolean,
    controlsEnabled: Boolean
) {
    if (beforeImage == null && afterImage == null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_MIN_HEIGHT)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    text = "No preview available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // The divider lives here and is only read while drawing, so dragging redraws one canvas
    // instead of recomposing the whole screen on every frame.
    var split by remember { mutableFloatStateOf(0.5f) }
    val canCompare = beforeImage != null && afterImage != null
    val dividerEnabled = controlsEnabled && canCompare
    val splitColor = MaterialTheme.colorScheme.onSurface
    val handleInk = MaterialTheme.colorScheme.background
    var panelWidthPx by remember { mutableFloatStateOf(0f) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(320)) }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            val visibleImage = beforeImage ?: afterImage
            val imageAspect = if (visibleImage != null && visibleImage.width > 0 && visibleImage.height > 0) {
                visibleImage.width.toFloat() / visibleImage.height.toFloat()
            } else {
                1f
            }

            val panelHeight = if (imageAspect > 0f) {
                (maxWidth / imageAspect)
                    .coerceAtLeast(PREVIEW_MIN_FITTED_HEIGHT)
                    .coerceAtMost(PREVIEW_MAX_HEIGHT)
            } else {
                PREVIEW_MIN_HEIGHT
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight)
            ) {
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            alpha = reveal.value
                            val grow = 0.97f + 0.03f * reveal.value
                            scaleX = grow
                            scaleY = grow
                        }
                        .onSizeChanged { panelSize ->
                            panelWidthPx = panelSize.width.toFloat()
                        }
                        .then(
                            if (dividerEnabled) {
                                Modifier.pointerInput(dividerEnabled) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            if (panelWidthPx > 0f) {
                                                split = (offset.x / panelWidthPx).coerceIn(0.05f, 0.95f)
                                            }
                                        },
                                        onHorizontalDrag = { change, dragAmount ->
                                            change.consume()
                                            if (panelWidthPx > 0f) {
                                                split = (split + dragAmount / panelWidthPx).coerceIn(0.05f, 0.95f)
                                            }
                                        }
                                    )
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    val sharedBounds = run {
                        val imageForBounds = beforeImage ?: afterImage
                        if (imageForBounds == null) {
                            Rect(0f, 0f, 0f, 0f)
                        } else {
                            imageForBounds.let { image ->
                                val sourceWidth = image.width.toFloat()
                                val sourceHeight = image.height.toFloat()

                                if (sourceWidth <= 0f || sourceHeight <= 0f || size.width <= 0f || size.height <= 0f) {
                                    Rect(0f, 0f, 0f, 0f)
                                } else {
                                    val scale = min(size.width / sourceWidth, size.height / sourceHeight)
                                    val drawWidth = sourceWidth * scale
                                    val drawHeight = sourceHeight * scale

                                    Rect(
                                        (size.width - drawWidth) / 2f,
                                        (size.height - drawHeight) / 2f,
                                        (size.width + drawWidth) / 2f,
                                        (size.height + drawHeight) / 2f
                                    )
                                }
                            }
                        }
                    }

                    fun drawContainedImage(image: ImageBitmap) {
                        val bounds = sharedBounds
                        if (bounds.width <= 0f || bounds.height <= 0f) {
                            return
                        }

                        drawImage(
                            image = image,
                            srcOffset = IntOffset(0, 0),
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()),
                            dstSize = IntSize(
                                bounds.width.roundToInt().coerceAtLeast(1),
                                bounds.height.roundToInt().coerceAtLeast(1)
                            )
                        )
                    }

                    if (sharedBounds.width > 0f && sharedBounds.height > 0f) {
                        beforeImage?.let {
                            drawContainedImage(it)
                        }

                        afterImage?.let { compressedImage ->
                            if (dividerEnabled) {
                                val dividerX = size.width * split
                                clipRect(
                                    left = 0f,
                                    top = 0f,
                                    right = dividerX,
                                    bottom = size.height
                                ) {
                                    drawContainedImage(compressedImage)
                                }

                                drawLine(
                                    color = splitColor,
                                    start = Offset(dividerX, sharedBounds.top),
                                    end = Offset(dividerX, sharedBounds.bottom),
                                    strokeWidth = 3.dp.toPx()
                                )

                                // A round grab handle with arrows, so the divider reads as draggable.
                                val handle = Offset(dividerX, sharedBounds.center.y)
                                val radius = 20.dp.toPx()
                                drawCircle(splitColor, radius, handle)
                                val arrow = 5.dp.toPx()
                                val gap = 4.dp.toPx()
                                drawPath(
                                    Path().apply {
                                        moveTo(handle.x - gap, handle.y - arrow)
                                        lineTo(handle.x - gap - arrow, handle.y)
                                        lineTo(handle.x - gap, handle.y + arrow)
                                        close()
                                        moveTo(handle.x + gap, handle.y - arrow)
                                        lineTo(handle.x + gap + arrow, handle.y)
                                        lineTo(handle.x + gap, handle.y + arrow)
                                        close()
                                    },
                                    handleInk
                                )
                            } else {
                                drawContainedImage(compressedImage)
                            }
                        }
                    }
                }
                if (dividerEnabled) {
                    CompareLabel("Compressed", Modifier.align(Alignment.TopStart))
                    CompareLabel("Original", Modifier.align(Alignment.TopEnd))
                }
            }
        }

    }
}

@Composable
private fun CompareLabel(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier
            .padding(10.dp)
            .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
