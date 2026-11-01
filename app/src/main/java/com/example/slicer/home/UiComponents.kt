package com.example.slicer.home

import com.example.slicer.core.BatchFileResult
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.ProcessingProgress
import com.example.slicer.core.isJpegFileName
import com.example.slicer.shared.formatByteCount
import com.example.slicer.shared.formatSavedPercentage

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import com.example.slicer.ui.theme.SliceBurst
import com.example.slicer.ui.theme.SliceProgressIndicator
import com.example.slicer.ui.theme.SlicerNumbers
import com.example.slicer.ui.theme.savingsSlice
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.example.slicer.ui.theme.SlicerTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
        val countStyle = MaterialTheme.typography.titleLarge.copy(
            fontFamily = SlicerNumbers,
            fontWeight = FontWeight.Black,
            fontSize = 44.sp,
            lineHeight = 52.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Each finished image rolls the count up like an odometer.
            AnimatedContent(
                targetState = progress.completed,
                transitionSpec = {
                    (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                },
                label = "completed-count"
            ) { completed ->
                Text(text = "$completed", style = countStyle, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = " / ${progress.total}",
                style = countStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SliceProgressIndicator(
            progress = ratio,
            modifier = Modifier.fillMaxWidth()
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
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = SlicerNumbers,
            fontWeight = FontWeight.Black,
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
    modifier: Modifier = Modifier,
    celebrate: Boolean = false,
    onCelebrated: () -> Unit = {}
) {
    // Captured once, so the caller can mark the run as celebrated straight away without cutting
    // the animation short. Owned outside the list, so scrolling the card away does not replay it.
    val celebrateNow = remember { celebrate && summary.cancelledCount == 0 && totalSavedBytes > 0L }
    val countUp = remember { Animatable(if (celebrateNow) 0f else 1f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        if (celebrate) onCelebrated()
        if (celebrateNow) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            countUp.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
        }
    }

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

                Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .savingsSlice()
                        .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${formatByteCount((totalSavedBytes * countUp.value).toLong())} saved",
                        style = MaterialTheme.typography.headlineMedium,
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
                    SliceBurst(play = celebrateNow, modifier = Modifier.matchParentSize())
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
    // Non-JPEG images were copied byte for byte, so there is nothing to compare.
    val copiedAsIs = result.decision == ProcessingDecision.CopiedOriginal && !isJpegFileName(result.inputName)
    val decisionLabel = when (result.decision) {
        ProcessingDecision.Compressed -> "Compressed"
        ProcessingDecision.CopiedOriginal -> if (copiedAsIs) "Copied as-is" else "Kept original"
        ProcessingDecision.Failed -> "Failed"
        ProcessingDecision.Cancelled -> "Cancelled"
    }

    val canOpenCompare = result.decision != ProcessingDecision.Failed &&
        result.decision != ProcessingDecision.Cancelled && result.outputUri != null && !copiedAsIs

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

            // Byte counts are emphasized; the trailing note stays regular weight.
            val sizes = buildAnnotatedString {
                val emphasis = SpanStyle(fontWeight = FontWeight.SemiBold)
                if (copiedAsIs) {
                    withStyle(emphasis) { append(formatByteCount(result.outputBytes)) }
                } else if (result.inputBytes > 0L) {
                    withStyle(emphasis) {
                        append("${formatByteCount(result.inputBytes)} -> ${formatByteCount(result.outputBytes)}")
                    }
                    formatSavedPercentage(result.inputBytes, result.outputBytes)?.let { append(" · $it") }
                } else {
                    append("Output size: ")
                    withStyle(emphasis) { append(formatByteCount(result.outputBytes)) }
                }
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
                    color = if (result.decision == ProcessingDecision.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
