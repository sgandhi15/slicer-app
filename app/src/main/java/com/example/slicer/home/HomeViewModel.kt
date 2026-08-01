package com.example.slicer.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import com.example.slicer.SlicerApplication
import com.example.slicer.batch.BatchEntity
import com.example.slicer.batch.BatchItemEntity
import com.example.slicer.batch.BatchItemState
import com.example.slicer.batch.BatchState
import com.example.slicer.core.BatchFileResult
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.ProcessingProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class DurableBatchUiState(
    val batchId: String? = null,
    val batchState: BatchState? = null,
    val progress: ProcessingProgress = ProcessingProgress(),
    val results: List<BatchFileResult> = emptyList(),
    val totalInputBytes: Long = 0L,
    val totalOutputBytes: Long = 0L,
    val totalSavedBytes: Long = 0L,
    val status: String? = null,
    val isStarting: Boolean = false,
    val error: String? = null
) {
    val isProcessing: Boolean
        get() = batchState?.isActive == true

    val isTerminal: Boolean
        get() = batchState?.isTerminal == true

    val needsDestination: Boolean
        get() = batchState == BatchState.NeedsDestination

    val canResume: Boolean
        get() = batchState == BatchState.Retrying
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as SlicerApplication).batchRepository
    private val dismissedBatchId = MutableStateFlow<String?>(null)
    private val isStarting = MutableStateFlow(false)
    private val startError = MutableStateFlow<String?>(null)
    @Volatile
    private var startingCancellationRequested = false

    private val persistedState = repository.observeLatestBatch().flatMapLatest { batch ->
        if (batch == null) {
            flowOf(null)
        } else {
            repository.observeItems(batch.id).map { items -> batch to items }
        }
    }

    val uiState = combine(
        persistedState,
        dismissedBatchId,
        isStarting,
        startError
    ) { persisted, dismissedId, starting, error ->
        val batch = persisted?.first?.takeUnless { it.id == dismissedId }
        val items = if (batch == null) emptyList() else persisted.second
        batch.toUiState(items, starting, error)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DurableBatchUiState()
    )

    fun startBatch(sourceUris: List<Uri>, destinationUri: Uri, quality: Int) {
        if (isStarting.value) return
        isStarting.value = true
        startError.value = null
        startingCancellationRequested = false
        viewModelScope.launch {
            try {
                val batchId = repository.createAndEnqueueBatch(
                    sourceUris = sourceUris,
                    destinationUri = destinationUri,
                    quality = quality,
                    cancellationRequested = { startingCancellationRequested }
                )
                if (startingCancellationRequested) {
                    repository.requestCancellation(batchId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                startError.value = error.message ?: "Could not create a durable batch."
            } finally {
                isStarting.value = false
            }
        }
    }

    fun cancelBatch() {
        if (isStarting.value) {
            startingCancellationRequested = true
        }
        val batchId = uiState.value.batchId
        viewModelScope.launch {
            if (batchId != null) repository.requestCancellation(batchId) else repository.cancelActiveBatches()
        }
    }

    fun replaceDestination(destinationUri: Uri) {
        val batchId = uiState.value.batchId ?: return
        startError.value = null
        viewModelScope.launch {
            try {
                repository.replaceDestinationAndResume(batchId, destinationUri)
            } catch (error: Exception) {
                startError.value = error.message ?: "Could not resume with the selected output folder."
            }
        }
    }

    fun resumeBatch() {
        viewModelScope.launch { repository.resumeIncompleteBatches() }
    }

    fun dismissCurrentBatch() {
        val current = uiState.value
        if (current.isTerminal) current.batchId?.let { dismissedBatchId.value = it }
        startError.value = null
    }

    fun clearError() {
        startError.value = null
    }
}

private fun BatchEntity?.toUiState(
    items: List<BatchItemEntity>,
    isStarting: Boolean,
    error: String?
): DurableBatchUiState {
    if (this == null) return DurableBatchUiState(isStarting = isStarting, error = error)

    val currentItem = items.firstOrNull { it.state == BatchItemState.Processing }
    val results = items.filter { it.state.isTerminal }.map { it.toResult() }
    val status = when (state) {
        BatchState.Queued -> "Preparing batch..."
        BatchState.Running -> "Compressing images..."
        BatchState.Retrying -> lastError ?: "Waiting to retry batch processing..."
        BatchState.NeedsDestination -> lastError ?: "Choose a new output folder to continue."
        BatchState.Cancelling -> "Cancelling after the current image..."
        BatchState.Completed -> "Done. $completedCount files written."
        BatchState.CompletedWithFailures ->
            "Done. Compressed: $compressedCount, kept original: $copiedCount, failed: $failedCount."
        BatchState.Cancelled -> {
            val processedCount = compressedCount + copiedCount + failedCount
            "Batch cancelled. $processedCount processed, $cancelledCount cancelled."
        }
        BatchState.Failed -> lastError ?: "Batch processing failed."
    }
    return DurableBatchUiState(
        batchId = id,
        batchState = state,
        progress = ProcessingProgress(
            total = totalCount,
            completed = completedCount,
            compressedCount = compressedCount,
            copiedCount = copiedCount,
            failedCount = failedCount,
            cancelledCount = cancelledCount,
            currentFile = currentItem?.inputName
        ),
        results = results,
        totalInputBytes = totalInputBytes,
        totalOutputBytes = totalOutputBytes,
        totalSavedBytes = totalSavedBytes,
        status = status,
        isStarting = isStarting,
        error = error
    )
}

private fun BatchItemEntity.toResult(): BatchFileResult = BatchFileResult(
    inputUri = sourceUri.toUri(),
    inputName = inputName
        ?: sourceUri.toUri().lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
        ?: "image.jpg",
    outputUri = outputUri?.toUri(),
    decision = when (state) {
        BatchItemState.Compressed -> ProcessingDecision.Compressed
        BatchItemState.CopiedOriginal -> ProcessingDecision.CopiedOriginal
        BatchItemState.Cancelled -> ProcessingDecision.Cancelled
        BatchItemState.Failed -> ProcessingDecision.Failed
        BatchItemState.Queued,
        BatchItemState.Processing -> error("Only terminal items can be shown as results.")
    },
    inputBytes = inputBytes,
    outputBytes = outputBytes,
    message = message
)
