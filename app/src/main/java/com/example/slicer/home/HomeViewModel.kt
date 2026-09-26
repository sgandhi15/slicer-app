package com.example.slicer.home

import android.app.Application
import android.net.Uri
import android.os.Trace
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map as mapPagingData
import com.example.slicer.SlicerApplication
import com.example.slicer.batch.BatchEntity
import com.example.slicer.batch.BatchItemEntity
import com.example.slicer.batch.BatchItemState
import com.example.slicer.batch.BatchState
import com.example.slicer.core.BatchFileResult
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.ProcessingProgress
import com.example.slicer.storage.ContentResolverImageSelectionValidator
import com.example.slicer.storage.ImageSelectionOutcome
import com.example.slicer.storage.ImageSelectionValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal data class ImageSelectionUiState(
    val selectedImages: List<Uri> = emptyList(),
    val isValidating: Boolean = false,
    val validatedCount: Int = 0,
    val totalCount: Int = 0,
    val unsupportedUris: List<Uri> = emptyList(),
    val inaccessibleUris: List<Uri> = emptyList(),
    val ignoredCount: Int = 0,
    internal val requestId: Long = 0L
)

internal data class DurableBatchUiState(
    val batchId: String? = null,
    val batchState: BatchState? = null,
    val progress: ProcessingProgress = ProcessingProgress(),
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
internal class HomeViewModel internal constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val imageSelectionValidator: ImageSelectionValidator,
    private val validationDispatcher: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(MAX_SELECTION_VALIDATION_CONCURRENCY)
) : AndroidViewModel(application) {
    constructor(application: Application, savedStateHandle: SavedStateHandle) : this(
        application = application,
        savedStateHandle = savedStateHandle,
        imageSelectionValidator = ContentResolverImageSelectionValidator(application)
    )

    private val repository = (application as SlicerApplication).batchRepository
    private val dismissedBatchId = MutableStateFlow<String?>(null)
    private val isStarting = MutableStateFlow(false)
    private val startError = MutableStateFlow<String?>(null)
    private val selectionRequestIds = AtomicLong(0L)
    private var selectionValidationJob: Job? = null
    private val _selectionState = MutableStateFlow(
        ImageSelectionUiState(selectedImages = restoreSelectedImages())
    )
    val selectionState: StateFlow<ImageSelectionUiState> = _selectionState.asStateFlow()
    @Volatile
    private var startingCancellationRequested = false

    private val persistedState = repository.observeLatestBatch().flatMapLatest { batch ->
        if (batch == null) {
            flowOf(null)
        } else {
            repository.observeCurrentItem(batch.id).map { currentItem -> batch to currentItem }
        }
    }

    val uiState = combine(
        persistedState,
        dismissedBatchId,
        isStarting,
        startError
    ) { persisted, dismissedId, starting, error ->
        val batch = persisted?.first?.takeUnless { it.id == dismissedId }
        val currentItem = if (batch == null) null else persisted.second
        batch.toUiState(currentItem, starting, error)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DurableBatchUiState()
    )

    val resultPages = repository.observeLatestBatch()
        .map { batch -> batch?.id }
        .distinctUntilChanged()
        .flatMapLatest { batchId ->
            if (batchId == null) {
                flowOf(PagingData.empty<BatchItemEntity>())
            } else {
                repository.pagedResults(batchId)
            }
        }
        .map { pagingData -> pagingData.mapPagingData { item -> item.toResult() } }
        .cachedIn(viewModelScope)

    fun validateImageSelection(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val requestId = selectionRequestIds.incrementAndGet()
        selectionValidationJob?.cancel()
        saveSelectedImages(emptyList())
        _selectionState.value = ImageSelectionUiState(
            isValidating = true,
            totalCount = minOf(uris.size, MAX_SELECTED_IMAGES),
            requestId = requestId
        )

        selectionValidationJob = viewModelScope.launch {
            val distinctUris = withContext(Dispatchers.Default) { uris.distinct() }
            val acceptedUris = distinctUris.take(MAX_SELECTED_IMAGES)
            val ignoredCount = distinctUris.size - acceptedUris.size
            _selectionState.update { state ->
                if (state.requestId == requestId) {
                    state.copy(totalCount = acceptedUris.size, ignoredCount = ignoredCount)
                } else {
                    state
                }
            }
            val completedCount = AtomicInteger(0)
            val outcomes = supervisorScope {
                val nextIndex = AtomicInteger(0)
                val results = arrayOfNulls<Pair<Uri, ImageSelectionOutcome>>(acceptedUris.size)
                List(minOf(MAX_SELECTION_VALIDATION_CONCURRENCY, acceptedUris.size)) {
                    async(validationDispatcher) {
                        while (true) {
                            val index = nextIndex.getAndIncrement()
                            if (index >= acceptedUris.size) break
                            val uri = acceptedUris[index]
                            val outcome = try {
                                imageSelectionValidator.validate(uri)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                ImageSelectionOutcome.Inaccessible
                            }
                            currentCoroutineContext().ensureActive()
                            results[index] = uri to outcome
                            val completed = completedCount.incrementAndGet()
                            _selectionState.update { state ->
                                if (state.requestId != requestId) state else state.copy(validatedCount = completed)
                            }
                        }
                    }
                }.awaitAll()
                results.filterNotNull()
            }

            val selectedImages = outcomes.mapNotNull { (uri, outcome) ->
                uri.takeIf { outcome == ImageSelectionOutcome.Supported }
            }
            val unsupportedUris = outcomes.mapNotNull { (uri, outcome) ->
                uri.takeIf { outcome == ImageSelectionOutcome.Unsupported }
            }
            val inaccessibleUris = outcomes.mapNotNull { (uri, outcome) ->
                uri.takeIf { outcome == ImageSelectionOutcome.Inaccessible }
            }
            _selectionState.update { state ->
                if (state.requestId != requestId) {
                    state
                } else {
                    state.copy(
                        selectedImages = selectedImages,
                        isValidating = false,
                        validatedCount = acceptedUris.size,
                        unsupportedUris = unsupportedUris,
                        inaccessibleUris = inaccessibleUris
                    )
                }
            }
            if (_selectionState.value.requestId == requestId) saveSelectedImages(selectedImages)
        }
    }

    fun removeSelectedImage(uri: Uri) {
        _selectionState.update { state ->
            state.copy(selectedImages = state.selectedImages - uri)
        }
        saveSelectedImages(_selectionState.value.selectedImages)
    }

    fun clearImageSelection() {
        val requestId = selectionRequestIds.incrementAndGet()
        selectionValidationJob?.cancel()
        _selectionState.value = ImageSelectionUiState(requestId = requestId)
        saveSelectedImages(emptyList())
    }

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

    private fun restoreSelectedImages(): List<Uri> {
        return savedStateHandle.get<ArrayList<String>>(SELECTED_IMAGES_KEY)
            ?.map(Uri::parse)
            .orEmpty()
    }

    private fun saveSelectedImages(images: List<Uri>) {
        savedStateHandle[SELECTED_IMAGES_KEY] = ArrayList(images.map(Uri::toString))
    }

    private companion object {
        const val MAX_SELECTION_VALIDATION_CONCURRENCY = 4
        const val SELECTED_IMAGES_KEY = "selected_images"
    }
}

private fun BatchEntity?.toUiState(
    currentItem: BatchItemEntity?,
    isStarting: Boolean,
    error: String?
): DurableBatchUiState = traceUiStateMapping {
    if (this == null) return@traceUiStateMapping DurableBatchUiState(isStarting = isStarting, error = error)

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
        totalInputBytes = totalInputBytes,
        totalOutputBytes = totalOutputBytes,
        totalSavedBytes = totalSavedBytes,
        status = status,
        isStarting = isStarting,
        error = error
    )
}

private inline fun <T> traceUiStateMapping(block: () -> T): T {
    Trace.beginSection(UI_STATE_MAPPING_TRACE)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

private const val UI_STATE_MAPPING_TRACE = "Slicer UI state"
internal const val MAX_SELECTED_IMAGES = 400

private fun BatchItemEntity.toResult(): BatchFileResult = BatchFileResult(
    id = id,
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
