package com.example.slicer.home

import com.example.slicer.storage.isDefaultOutputFolder
import com.example.slicer.storage.loadTileThumbnail
import com.example.slicer.storage.readableName

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.example.slicer.ui.theme.SlicerTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import com.example.slicer.ui.theme.SlicerOutlinedButton
import com.example.slicer.ui.theme.pressSquish
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BatchSetupContent(
    selectedImages: List<Uri>,
    isValidating: Boolean,
    validatedCount: Int,
    validationTotal: Int,
    controlsEnabled: Boolean,
    onSelectImages: () -> Unit,
    onSelectFolder: () -> Unit,
    onRemoveImage: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    // Fade between the empty picker and the grid instead of snapping when a selection lands.
    AnimatedContent(
        targetState = selectedImages.isEmpty(),
        transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
        modifier = modifier,
        label = "setup-content"
    ) { isEmpty ->
        if (isEmpty) {
            EmptySetupContent(isValidating, validatedCount, validationTotal, controlsEnabled, onSelectImages, onSelectFolder)
        } else {
            SelectedImagesGrid(selectedImages, isValidating, validatedCount, validationTotal, controlsEnabled, onSelectImages, onRemoveImage)
        }
    }
}

@Composable
private fun EmptySetupContent(
    isValidating: Boolean,
    validatedCount: Int,
    validationTotal: Int,
    controlsEnabled: Boolean,
    onSelectImages: () -> Unit,
    onSelectFolder: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Choose images",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                    letterSpacing = (-0.5).sp
                )
            )
            Text(
                "Make your photos smaller.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EmptyImagePicker(
                enabled = controlsEnabled,
                onClick = onSelectImages,
                modifier = Modifier.aspectRatio(1.4f)
            )
            SlicerOutlinedButton(
                onClick = onSelectFolder,
                enabled = controlsEnabled,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Choose a folder")
            }
        }

        if (isValidating) {
            SelectionValidationPanel(validatedCount, validationTotal)
        }

        Text(
            "On-device compression. Originals stay untouched.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SelectedImagesGrid(
    selectedImages: List<Uri>,
    isValidating: Boolean,
    validatedCount: Int,
    validationTotal: Int,
    controlsEnabled: Boolean,
    onSelectImages: () -> Unit,
    onRemoveImage: (Uri) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SelectedImagesHeader(
                enabled = controlsEnabled,
                onSelectImages = onSelectImages
            )
        }

        if (isValidating) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SelectionValidationPanel(validatedCount, validationTotal)
            }
        }

        items(
            items = selectedImages,
            key = { it.toString() }
        ) { uri ->
            SelectedImageTile(
                uri = uri,
                enabled = controlsEnabled,
                onRemove = { onRemoveImage(uri) },
                // Neighbours slide into the gap when a tile is removed.
                modifier = Modifier.animateItem()
            )
        }

    }
}

@Composable
private fun SelectionValidationPanel(completed: Int, total: Int) {
    val progress = if (total <= 0) 0f else completed.toFloat() / total.toFloat()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("image-validation-progress"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Validating $completed of $total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EmptyImagePicker(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pressSquish(interactionSource, pressedScale = 0.97f)
            .testTag("batch-image-picker"),
        enabled = enabled,
        onClick = onClick,
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            Text(
                text = "＋",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                text = "Select images",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                text = "Up to $MAX_SELECTED_IMAGES images",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun SelectedImagesHeader(
    enabled: Boolean,
    onSelectImages: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Selected images",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        TextButton(
            onClick = onSelectImages,
            enabled = enabled,
            modifier = Modifier.testTag("change-image-selection")
        ) {
            Text("Change")
        }
    }
}

@Composable
private fun SelectedImageTile(
    uri: Uri,
    enabled: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val key = uri.toString()
    var bitmap by remember(uri) { mutableStateOf(ThumbnailCache.get(key)) }
    // Tiles already in the cache appear instantly; new ones fade in rather than pop.
    val imageAlpha by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(220), label = "thumb-fade")

    LaunchedEffect(uri) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            context.loadTileThumbnail(uri)
        }
        if (loaded != null) {
            ThumbnailCache.put(key, loaded)
            bitmap = loaded
        }
    }

    Box(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("selected-image-${uri}")
    ) {
        bitmap?.let { loadedBitmap ->
            Image(
                bitmap = remember(loadedBitmap) { loadedBitmap.asImageBitmap() },
                contentDescription = "Selected image",
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha },
                contentScale = ContentScale.Crop
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                .clickable(enabled = enabled, onClick = onRemove)
                .semantics { contentDescription = "Remove selected image" }
                .testTag("remove-image-${uri}"),
            contentAlignment = Alignment.Center
        ) {
            TrashIcon(
                modifier = Modifier.size(15.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Keeps decoded tiles around, so scrolling back up or returning to this screen does not decode
 * the same photos again. Bitmaps are never recycled by hand: one may still be on screen when it is
 * evicted, and the garbage collector frees it once nothing draws it.
 */
private object ThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.width * value.height * 4
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

@Composable
private fun TrashIcon(
    modifier: Modifier,
    color: androidx.compose.ui.graphics.Color
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.7.dp.toPx()
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.28f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.28f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.38f, size.height * 0.15f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.62f, size.height * 0.15f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawPath(
            path = Path().apply {
                moveTo(size.width * 0.27f, size.height * 0.36f)
                lineTo(size.width * 0.33f, size.height * 0.85f)
                lineTo(size.width * 0.67f, size.height * 0.85f)
                lineTo(size.width * 0.73f, size.height * 0.36f)
            },
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

@Composable
internal fun DestinationSection(
    outputFolder: Uri,
    controlsEnabled: Boolean,
    onSelectOutput: () -> Unit,
    onForgetOutput: () -> Unit
) {
    val isDefaultOutputFolder = outputFolder.isDefaultOutputFolder()
    val folderName = outputFolder.readableName()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Save to",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = controlsEnabled, onClick = onSelectOutput),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DestinationFolderMark()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folderName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Change destination",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (!isDefaultOutputFolder) {
                    DestinationResetButton(
                        enabled = controlsEnabled,
                        onClick = onForgetOutput
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationResetButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "Reset to default destination" }
    ) {
        val color = MaterialTheme.colorScheme.onSurface
        Canvas(modifier = Modifier.size(22.dp)) {
            val resetArrow = Path().apply {
                moveTo(size.width * 0.82f, size.height * 0.25f)
                cubicTo(
                    size.width * 0.64f,
                    size.height * 0.08f,
                    size.width * 0.34f,
                    size.height * 0.10f,
                    size.width * 0.19f,
                    size.height * 0.32f
                )
                cubicTo(
                    size.width * 0.04f,
                    size.height * 0.54f,
                    size.width * 0.16f,
                    size.height * 0.80f,
                    size.width * 0.39f,
                    size.height * 0.89f
                )
                cubicTo(
                    size.width * 0.61f,
                    size.height * 0.97f,
                    size.width * 0.84f,
                    size.height * 0.84f,
                    size.width * 0.89f,
                    size.height * 0.63f
                )
                moveTo(size.width * 0.66f, size.height * 0.25f)
                lineTo(size.width * 0.83f, size.height * 0.25f)
                lineTo(size.width * 0.81f, size.height * 0.08f)
            }
            drawPath(
                path = resetArrow,
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun DestinationFolderMark() {
    val colorScheme = MaterialTheme.colorScheme
    Canvas(modifier = Modifier.size(28.dp)) {
        val folder = Path().apply {
            moveTo(size.width * 0.06f, size.height * 0.24f)
            lineTo(size.width * 0.38f, size.height * 0.24f)
            lineTo(size.width * 0.48f, size.height * 0.36f)
            lineTo(size.width * 0.94f, size.height * 0.36f)
            lineTo(size.width * 0.94f, size.height * 0.84f)
            lineTo(size.width * 0.06f, size.height * 0.84f)
            close()
        }
        drawPath(folder, colorScheme.primaryContainer)
        drawPath(
            path = folder,
            color = colorScheme.primary,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
