package com.example.slicer

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged

import kotlin.math.min
import kotlin.math.roundToInt

private val PREVIEW_MIN_HEIGHT = 320.dp
private val PREVIEW_MAX_HEIGHT = 560.dp

@Composable
internal fun AppHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                contentDescription = "Slicer logo",
                modifier = Modifier.size(50.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Slicer",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "Simple and local-first image compression.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

    }
}

@Composable
internal fun WelcomeScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .testTag("slicer-welcome-screen"),
        horizontalAlignment = Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                contentDescription = "Slicer logo",
                modifier = Modifier.size(84.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Welcome to Slicer",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Reduce image sizes quickly and privately on device.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Pick a few photos, adjust quality, and save optimized outputs to a folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text("Get started", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
internal fun SetupSection(
    selectedCount: Int,
    outputFolder: Uri,
    onSelectImages: () -> Unit,
    onSelectOutput: () -> Unit,
    onForgetOutput: () -> Unit,
    controlsEnabled: Boolean
) {
    val isDefaultOutputFolder = outputFolder.isDefaultOutputFolder()

    SectionCard(title = "Set up your batch") {
        val selectedFilesText = when (selectedCount) {
            0 -> "No images selected yet"
            1 -> "1 image selected"
            else -> "$selectedCount images selected"
        }

        SetupWizardStep(
            stepNumber = 1,
            title = "Choose images",
            summary = selectedFilesText,
            actionText = if (selectedCount == 0) "Select images" else "Add more images",
            onAction = onSelectImages,
            enabled = controlsEnabled
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 1.dp)
        SetupWizardStep(
            stepNumber = 2,
            title = "Choose destination",
            summary = outputFolder.readableName(),
            actionText = if (isDefaultOutputFolder) "Select output folder" else "Change output folder",
            onAction = onSelectOutput,
            enabled = controlsEnabled,
            secondaryActionText = if (isDefaultOutputFolder) null else "Clear destination",
            onSecondaryAction = onForgetOutput
        )
    }
}

@Composable
private fun SetupWizardStep(
    stepNumber: Int,
    title: String,
    summary: String,
    actionText: String,
    onAction: () -> Unit,
    enabled: Boolean,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.size(30.dp)
            ) {
                Text(
                    text = stepNumber.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Button(
            onClick = onAction,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text(actionText)
        }

        if (secondaryActionText != null && onSecondaryAction != null) {
            OutlinedButton(
                onClick = onSecondaryAction,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text(secondaryActionText)
            }
        }
    }
}

@Composable
internal fun QualitySection(
    quality: Int,
    onQualityChange: (Int) -> Unit,
    onPresetSelected: (Int) -> Unit,
    controlsEnabled: Boolean
) {
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val presetLabel = QUALITY_PRESETS.find { it.quality == quality }?.label ?: "Custom"
    SectionCard(title = "Quality") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Compression target",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

            }

            Text(
                text = presetLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QUALITY_PRESETS.forEach { preset ->
                val isSelected = quality == preset.quality
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = controlsEnabled) { onPresetSelected(preset.quality) }
                        .height(58.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = when (preset.label) {
                                    "Small" -> "Prioritize smaller files."
                                    "Balanced" -> "Balanced quality and size."
                                    else -> "Preserve more detail while reducing size."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        RadioButton(
                            selected = isSelected,
                            onClick = { onPresetSelected(preset.quality) },
                            enabled = controlsEnabled
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = { showAdvanced = !showAdvanced },
            enabled = controlsEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text(if (showAdvanced) "Hide manual controls" else "Adjust manually")
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (showAdvanced) {
            Spacer(modifier = Modifier.height(4.dp))

            Slider(
                value = quality.toFloat(),
                onValueChange = { onQualityChange(it.roundToInt().coerceIn(MIN_QUALITY, MAX_QUALITY)) },
                valueRange = MIN_QUALITY.toFloat()..MAX_QUALITY.toFloat(),
                enabled = controlsEnabled
            )

        }
    }
}

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
    var panelWidthPx by remember { mutableStateOf(0f) }
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

@Composable
internal fun ProcessingPanel(progress: ProcessingProgress) {
    SectionCard(title = "Processing") {
        val ratio = if (progress.total <= 0) {
            0f
        } else {
            progress.completed.toFloat() / progress.total.toFloat()
        }

        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            progress = { ratio }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Progress: ${progress.completed} of ${progress.total}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "Compressed: ${progress.compressedCount}  •  Kept original: ${progress.copiedCount}  •  Failed: ${progress.failedCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        progress.currentFile?.let {
            Text(
                text = "Current: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun BatchResultsPanel(
    results: List<BatchFileResult>,
    onResultClick: (BatchFileResult) -> Unit,
    isResultListScrollable: Boolean = false,
    resultListModifier: Modifier = Modifier
) {
    if (results.isEmpty()) {
        NoticeText("No results yet.")
        return
    }

    val compressedCount = results.count { it.decision == ProcessingDecision.Compressed }
    val copiedCount = results.count { it.decision == ProcessingDecision.CopiedOriginal }
    val failedCount = results.count { it.decision == ProcessingDecision.Failed }
    val savedBytes = results.sumOf { result ->
        val input = result.inputBytes
        val output = result.outputBytes
        if (input > 0L && output > 0L && output < input) input - output else 0L
    }
    val totalInputBytes = results.sumOf { it.inputBytes.coerceAtLeast(0L) }
    val totalOutputBytes = results.sumOf { it.outputBytes.coerceAtLeast(0L) }
    val totalSavedPercent = formatSavedPercentage(totalInputBytes, totalOutputBytes)

    SectionCard(title = "Run summary") {
        Text(
            text = "Compressed: $compressedCount",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Kept original: $copiedCount",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = "Failed: $failedCount",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Saved total: ${formatByteCount(savedBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (totalSavedPercent != null) {
            Text(
                text = "Saved overall: ${totalSavedPercent}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    val listScrollModifier = if (isResultListScrollable) {
        resultListModifier.verticalScroll(rememberScrollState())
    } else {
        resultListModifier
    }

    SectionCard(
        title = "Last run files",
        modifier = listScrollModifier
    ) {
        results.forEachIndexed { index, result ->
            if (index > 0) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), thickness = 1.dp)
            }
            BatchResultRow(
                result = result,
                testTag = "slicer-result-row-${index}",
                onResultClick = {
                    if (result.decision != ProcessingDecision.Failed && result.outputUri != null) {
                        onResultClick(result)
                    }
                }
            )
        }
    }
}

@Composable
private fun BatchResultRow(
    result: BatchFileResult,
    testTag: String,
    onResultClick: () -> Unit
) {
    val decisionLabel = when (result.decision) {
        ProcessingDecision.Compressed -> "Compressed"
        ProcessingDecision.CopiedOriginal -> "Kept original"
        ProcessingDecision.Failed -> "Failed"
    }

    val canOpenCompare = result.decision != ProcessingDecision.Failed && result.outputUri != null

    val decisionColor = when (result.decision) {
        ProcessingDecision.Compressed -> MaterialTheme.colorScheme.primary
        ProcessingDecision.CopiedOriginal -> MaterialTheme.colorScheme.secondary
        ProcessingDecision.Failed -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (canOpenCompare) {
                    Modifier.clickable(
                        onClick = onResultClick
                    )
                } else {
                    Modifier
                }
            )
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.inputName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val sizes = if (result.inputBytes > 0L) {
                val savedPercent = formatSavedPercentage(result.inputBytes, result.outputBytes)
                if (savedPercent == null) {
                    "${formatByteCount(result.inputBytes)} → ${formatByteCount(result.outputBytes)}"
                } else {
                    "${formatByteCount(result.inputBytes)} → ${formatByteCount(result.outputBytes)}  ($savedPercent)"
                }
            } else {
                "Output size: ${formatByteCount(result.outputBytes)}"
            }

            Text(
                text = sizes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (result.message != null) {
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Surface(
            color = decisionColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(0.dp)
        ) {
            Text(
                text = decisionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = decisionColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
internal fun NoticeText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
