package com.example.slicer.home

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
private val PREVIEW_MAX_HEIGHT = 560.dp

@Composable
internal fun CompareScreen(
    comparePosition: Float,
    beforeImage: ImageBitmap?,
    afterImage: ImageBitmap?,
    isGeneratingComparison: Boolean,
    onCompareChange: (Float) -> Unit,
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
                splitPosition = comparePosition,
                onSplitChange = onCompareChange,
                controlsEnabled = controlsEnabled,
                showSlider = false
            )

            if (notice != null) {
                Spacer(modifier = Modifier.height(10.dp))
                NoticeText(message = notice)
            } else if (isGeneratingComparison) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                NoticeText(message = "Loading comparison images")
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
    splitPosition: Float,
    onSplitChange: (Float) -> Unit,
    controlsEnabled: Boolean,
    showSlider: Boolean
) {
    if (beforeImage == null && afterImage == null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            Text(
                text = "No preview available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val split = splitPosition.coerceIn(0.05f, 0.95f)
    val canCompare = beforeImage != null && afterImage != null
    val dividerEnabled = controlsEnabled && canCompare
    val splitColor = MaterialTheme.colorScheme.onSurface
    val splitColorSoft = splitColor.copy(alpha = 0.35f)
    var panelWidthPx by remember { mutableFloatStateOf(0f) }
    val currentSplit by rememberUpdatedState(split)
    val currentOnSplitChange by rememberUpdatedState(onSplitChange)

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
                    .coerceAtLeast(PREVIEW_MIN_HEIGHT)
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
                        .onSizeChanged { panelSize ->
                            panelWidthPx = panelSize.width.toFloat()
                        }
                        .then(
                            if (dividerEnabled) {
                                Modifier.pointerInput(dividerEnabled) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            if (panelWidthPx > 0f) {
                                                currentOnSplitChange((offset.x / panelWidthPx).coerceIn(0.05f, 0.95f))
                                            }
                                        },
                                        onHorizontalDrag = { change, dragAmount ->
                                            change.consume()
                                            if (panelWidthPx > 0f) {
                                                val nextSplit = currentSplit + (dragAmount / panelWidthPx)
                                                currentOnSplitChange(nextSplit.coerceIn(0.05f, 0.95f))
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
                                    start = Offset(dividerX, 0f),
                                    end = Offset(dividerX, size.height),
                                    strokeWidth = 3.dp.toPx()
                                )

                                drawLine(
                                    color = splitColorSoft,
                                    start = Offset(dividerX + 4.dp.toPx(), 0f),
                                    end = Offset(dividerX + 4.dp.toPx(), size.height),
                                    strokeWidth = 3.dp.toPx()
                                )
                            } else {
                                drawContainedImage(compressedImage)
                            }
                        }
                    }
                }
            }
        }

        if (showSlider) {
            Spacer(modifier = Modifier.height(12.dp))
            Slider(
                value = split,
                onValueChange = onSplitChange,
                valueRange = 0.05f..0.95f,
                enabled = dividerEnabled
            )
        }
    }
}
