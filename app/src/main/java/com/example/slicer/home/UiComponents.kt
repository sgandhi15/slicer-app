package com.example.slicer.home

import com.example.slicer.core.BatchFileResult
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.ProcessingProgress
import com.example.slicer.shared.formatByteCount
import com.example.slicer.shared.formatSavedPercentage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.example.slicer.ui.theme.SlicerTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.paging.LoadState

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
            modifier = Modifier.fillMaxWidth().height(6.dp)
                .clip(MaterialTheme.shapes.small),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            progress = { ratio }
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

        if (progress.failedCount > 0) {
            Text(
                text = "${progress.failedCount} failed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
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
    results: LazyPagingItems<BatchFileResult>,
    summary: ProcessingProgress,
    totalInputBytes: Long,
    totalOutputBytes: Long,
    totalSavedBytes: Long,
    onResultClick: (BatchFileResult) -> Unit,
    modifier: Modifier = Modifier
) {
    when (val refresh = results.loadState.refresh) {
        is LoadState.Loading -> if (results.itemCount == 0) {
            Column(
                modifier = modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            return
        }
        is LoadState.Error -> if (results.itemCount == 0) {
            PagingFailure(refresh.error.message ?: "Could not load results.", results::retry)
            return
        }
        is LoadState.NotLoading -> Unit
    }

    val totalSavedPercent = if (summary.failedCount == 0 && summary.cancelledCount == 0) {
        formatSavedPercentage(totalInputBytes, totalOutputBytes)
    } else {
        null
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("slicer-result-list")
    ) {
        item(key = "run-summary") {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Results",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), MaterialTheme.shapes.medium).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${formatByteCount(totalSavedBytes)} saved",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (totalSavedPercent != null) {
                        Text(
                            text = totalSavedPercent,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        ProcessingStat(
                            value = summary.compressedCount,
                            label = "Compressed",
                            modifier = Modifier.weight(1f)
                        )
                        ProcessingStat(
                            value = summary.copiedCount,
                            label = "Copied",
                            modifier = Modifier.weight(1f)
                        )
                        ProcessingStat(
                            value = summary.failedCount,
                            label = "Failed",
                            modifier = Modifier.weight(1f),
                            isError = summary.failedCount > 0
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Images",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        (results.loadState.refresh as? LoadState.Error)?.let { refresh ->
            item(key = "result-refresh-error") {
                PagingFailure(refresh.error.message ?: "Could not refresh results.", results::retry)
            }
        }
        when (val prepend = results.loadState.prepend) {
            is LoadState.Loading -> item(key = "result-prepend-loading") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }
            }
            is LoadState.Error -> item(key = "result-prepend-error") {
                PagingFailure(prepend.error.message ?: "Could not load earlier results.", results::retry)
            }
            is LoadState.NotLoading -> Unit
        }

        items(
            count = results.itemCount,
            key = results.itemKey { result -> "result-${result.id}" },
            contentType = results.itemContentType { result -> result.decision }
        ) { index ->
            val result = results[index] ?: return@items
            if (index > 0) {
                HorizontalDivider(thickness = 1.dp)
            }
            BatchResultRow(
                result = result,
                testTag = "slicer-result-row-${result.id}",
                onResultClick = {
                    if (result.decision != ProcessingDecision.Failed &&
                        result.decision != ProcessingDecision.Cancelled && result.outputUri != null
                    ) {
                        onResultClick(result)
                    }
                }
            )
        }
        when (val append = results.loadState.append) {
            is LoadState.Loading -> item(key = "result-page-loading") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }
            }
            is LoadState.Error -> item(key = "result-page-error") {
                PagingFailure(append.error.message ?: "Could not load more results.", results::retry)
            }
            is LoadState.NotLoading -> Unit
        }
        item(key = "result-list-bottom-space") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PagingFailure(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        TextButton(onClick = onRetry) { Text("Retry") }
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
        ProcessingDecision.Compressed -> MaterialTheme.colorScheme.onSurface
        ProcessingDecision.CopiedOriginal -> MaterialTheme.colorScheme.onSurfaceVariant
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
            color = when (result.decision) {
                ProcessingDecision.Compressed -> MaterialTheme.colorScheme.surfaceVariant
                ProcessingDecision.Failed -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.small
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
