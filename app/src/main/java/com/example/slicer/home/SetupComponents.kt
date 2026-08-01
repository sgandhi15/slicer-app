package com.example.slicer.home

import com.example.slicer.storage.isDefaultOutputFolder
import com.example.slicer.storage.loadBitmapForPreview
import com.example.slicer.storage.readableName

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

@Composable
internal fun BatchSetupContent(
    selectedImages: List<Uri>,
    status: String?,
    isValidating: Boolean,
    validatedCount: Int,
    validationTotal: Int,
    controlsEnabled: Boolean,
    onSelectImages: () -> Unit,
    onRemoveImage: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedImages.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EmptyImagePicker(
                enabled = controlsEnabled,
                onClick = onSelectImages,
                modifier = Modifier.weight(1f)
            )

            if (isValidating) {
                SelectionValidationPanel(validatedCount, validationTotal)
            }

            status?.let { message ->
                NoticeText(message = message)
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
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

        status?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                NoticeText(message = message)
            }
        }

        items(
            items = selectedImages,
            key = { it.toString() }
        ) { uri ->
            SelectedImageTile(
                uri = uri,
                enabled = controlsEnabled,
                onRemove = { onRemoveImage(uri) }
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("batch-image-picker"),
        enabled = enabled,
        onClick = onClick,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Select images",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Choose one or more JPEG files",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        var loadedBitmap: Bitmap? = null
        try {
            loadedBitmap = withContext(Dispatchers.IO) {
                context.loadBitmapForPreview(uri, maxDimension = 512)
            }
            bitmap = loadedBitmap
            awaitCancellation()
        } finally {
            if (bitmap === loadedBitmap) {
                bitmap = null
            }
            loadedBitmap?.recycle()
        }
    }

    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("selected-image-${uri}")
    ) {
        bitmap?.let { loadedBitmap ->
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = "Selected image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
                .clickable(enabled = enabled, onClick = onRemove)
                .semantics { contentDescription = "Remove selected image" }
                .testTag("remove-image-${uri}"),
            contentAlignment = Alignment.Center
        ) {
            TrashIcon(
                modifier = Modifier.size(15.dp),
                color = MaterialTheme.colorScheme.onError
            )
        }
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
            text = "Destination",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = controlsEnabled, onClick = onSelectOutput),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
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
                        color = MaterialTheme.colorScheme.primary
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
        val color = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }
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
