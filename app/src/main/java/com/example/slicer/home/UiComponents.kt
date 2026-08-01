package com.example.slicer.home

import com.example.slicer.core.BatchFileResult
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.ProcessingProgress
import com.example.slicer.shared.formatByteCount
import com.example.slicer.shared.formatSavedPercentage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ProcessingPanel(
    progress: ProcessingProgress,
    modifier: Modifier = Modifier
) {
    val ratio = if (progress.total <= 0) {
        0f
    } else {
        progress.completed.toFloat() / progress.total.toFloat()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Compressing images",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "${progress.completed} / ${progress.total}",
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 36.sp,
                lineHeight = 44.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            progress = { ratio }
        )

        Text(
            text = "${(ratio * 100).toInt()}% complete",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        progress.currentFile?.let { currentFile ->
            Text(
                text = currentFile,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            ProcessingStat(
                value = progress.compressedCount,
                label = "Compressed",
                modifier = Modifier.weight(1f)
            )
            ProcessingStat(
                value = progress.copiedCount,
                label = "Copied",
                modifier = Modifier.weight(1f)
            )
            ProcessingStat(
                value = progress.failedCount,
                label = "Failed",
                modifier = Modifier.weight(1f),
                isError = progress.failedCount > 0
            )
        }
    }
}

@Composable
private fun ProcessingStat(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun BatchResultsPanel(
    results: List<BatchFileResult>,
    onResultClick: (BatchFileResult) -> Unit,
    modifier: Modifier = Modifier,
    isResultListScrollable: Boolean = false,
    persistedInputBytes: Long? = null,
    persistedOutputBytes: Long? = null,
    persistedSavedBytes: Long? = null
) {
    if (results.isEmpty()) {
        NoticeText("No results yet.")
        return
    }

    val compressedCount = results.count { it.decision == ProcessingDecision.Compressed }
    val copiedCount = results.count { it.decision == ProcessingDecision.CopiedOriginal }
    val failedCount = results.count { it.decision == ProcessingDecision.Failed }
    val cancelledCount = results.count { it.decision == ProcessingDecision.Cancelled }
    val successfulResults = results.filter {
        it.decision == ProcessingDecision.Compressed || it.decision == ProcessingDecision.CopiedOriginal
    }
    val savedBytes = persistedSavedBytes ?: successfulResults.sumOf { result ->
        val input = result.inputBytes
        val output = result.outputBytes
        if (input > 0L && output > 0L && output < input) input - output else 0L
    }
    val totalInputBytes = persistedInputBytes
        ?: successfulResults.sumOf { it.inputBytes.coerceAtLeast(0L) }
    val totalOutputBytes = persistedOutputBytes
        ?: successfulResults.sumOf { it.outputBytes.coerceAtLeast(0L) }
    val totalSavedPercent = if (failedCount == 0 && cancelledCount == 0) {
        formatSavedPercentage(totalInputBytes, totalOutputBytes)
    } else {
        null
    }

    Text(
        text = "Run summary",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(modifier = Modifier.height(12.dp))

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "${formatByteCount(savedBytes)} saved",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (totalSavedPercent != null) {
            Text(
                text = "$totalSavedPercent smaller",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            ProcessingStat(
                value = compressedCount,
                label = "Compressed",
                modifier = Modifier.weight(1f)
            )
            ProcessingStat(
                value = copiedCount,
                label = "Copied",
                modifier = Modifier.weight(1f)
            )
            ProcessingStat(
                value = failedCount,
                label = "Failed",
                modifier = Modifier.weight(1f),
                isError = failedCount > 0
            )
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    Text(
        text = "Last run files",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(modifier = Modifier.height(12.dp))

    val listScrollModifier = if (isResultListScrollable) {
        modifier.verticalScroll(rememberScrollState())
    } else {
        modifier
    }

    Column(
        modifier = listScrollModifier.fillMaxWidth()
    ) {
        results.forEachIndexed { index, result ->
            if (index > 0) {
                HorizontalDivider(thickness = 1.dp)
            }
            BatchResultRow(
                result = result,
                testTag = "slicer-result-row-${index}",
                onResultClick = {
                    if (result.decision != ProcessingDecision.Failed &&
                        result.decision != ProcessingDecision.Cancelled && result.outputUri != null
                    ) {
                        onResultClick(result)
                    }
                }
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
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
        ProcessingDecision.Cancelled -> "Cancelled"
    }

    val canOpenCompare = result.decision != ProcessingDecision.Failed &&
        result.decision != ProcessingDecision.Cancelled && result.outputUri != null

    val decisionColor = when (result.decision) {
        ProcessingDecision.Compressed -> MaterialTheme.colorScheme.primary
        ProcessingDecision.CopiedOriginal -> MaterialTheme.colorScheme.secondary
        ProcessingDecision.Failed -> MaterialTheme.colorScheme.error
        ProcessingDecision.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (canOpenCompare) {
                    Modifier.clickable { onResultClick() }
                } else {
                    Modifier
                }
            )
            .padding(vertical = 12.dp)
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
                    "${formatByteCount(result.inputBytes)} -> ${formatByteCount(result.outputBytes)}"
                } else {
                    "${formatByteCount(result.inputBytes)} -> ${formatByteCount(result.outputBytes)}  ($savedPercent)"
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
