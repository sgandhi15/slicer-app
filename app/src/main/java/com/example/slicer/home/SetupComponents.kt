package com.example.slicer.home

import com.example.slicer.storage.isDefaultOutputFolder

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.slicer.storage.readableName

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

    SectionCard(title = "Batch setup") {
        val selectedFilesText = when (selectedCount) {
            0 -> "No images selected yet"
            1 -> "1 image selected"
            else -> "$selectedCount images selected"
        }

        SetupItem(
            title = "Choose images",
            summary = selectedFilesText,
            actionText = if (selectedCount == 0) "Select images" else "Change selection",
            onAction = onSelectImages,
            enabled = controlsEnabled
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 1.dp)
        SetupItem(
            title = "Destination",
            summary = outputFolder.readableName(),
            actionText = "Change destination",
            onAction = onSelectOutput,
            enabled = controlsEnabled,
            secondaryActionText = if (isDefaultOutputFolder) null else "Clear destination",
            onSecondaryAction = onForgetOutput
        )
    }
}

@Composable
private fun SetupItem(
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedButton(
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
