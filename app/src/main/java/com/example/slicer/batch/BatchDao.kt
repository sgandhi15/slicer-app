package com.example.slicer.batch

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class BatchDao {
    @Insert
    protected abstract suspend fun insertBatch(batch: BatchEntity)

    @Insert
    protected abstract suspend fun insertItems(items: List<BatchItemEntity>)

    @Query(
        """
        SELECT * FROM batches
        ORDER BY CASE
            WHEN state IN ('Queued', 'Running', 'Retrying', 'NeedsDestination', 'Cancelling') THEN 0
            ELSE 1
        END, createdAt DESC
        LIMIT 1
        """
    )
    abstract fun observeLatestBatch(): Flow<BatchEntity?>

    @Query(
        """
        SELECT * FROM batch_items
        WHERE batchId = :batchId
            AND state IN ('Compressed', 'CopiedOriginal', 'Failed', 'Cancelled')
        ORDER BY position
        """
    )
    abstract fun pagingResults(batchId: String): PagingSource<Int, BatchItemEntity>

    @Query(
        """
        SELECT * FROM batch_items
        WHERE batchId = :batchId AND state = 'Processing'
        ORDER BY position LIMIT 1
        """
    )
    abstract fun observeCurrentItem(batchId: String): Flow<BatchItemEntity?>

    @Query("SELECT * FROM batches WHERE id = :batchId")
    abstract suspend fun getBatch(batchId: String): BatchEntity?

    @Query("SELECT * FROM batch_items WHERE batchId = :batchId ORDER BY position")
    abstract suspend fun getItems(batchId: String): List<BatchItemEntity>

    @Query("SELECT * FROM batch_items WHERE id = :itemId")
    abstract suspend fun getItem(itemId: String): BatchItemEntity?

    @Query("SELECT * FROM batch_items WHERE state IN ('Queued', 'Processing')")
    abstract suspend fun getItemsRequiringSourcePermission(): List<BatchItemEntity>

    @Query("SELECT DISTINCT sourceUri FROM batch_items WHERE state IN ('Queued', 'Processing')")
    abstract suspend fun getRequiredSourceUris(): List<String>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM batch_items
            WHERE sourceUri = :sourceUri AND state IN ('Queued', 'Processing')
        )
        """
    )
    abstract suspend fun hasNonTerminalSourceReference(sourceUri: String): Boolean

    @Query(
        """
        SELECT id FROM batches
        WHERE state IN (:queuedState, :runningState, :retryingState, :cancellingState)
        ORDER BY createdAt ASC
        """
    )
    abstract suspend fun getActiveBatchIds(
        queuedState: BatchState = BatchState.Queued,
        runningState: BatchState = BatchState.Running,
        retryingState: BatchState = BatchState.Retrying,
        cancellingState: BatchState = BatchState.Cancelling
    ): List<String>

    @Query(
        """
        SELECT COUNT(*) FROM batches
        WHERE state IN (:queuedState, :runningState, :retryingState, :needsDestinationState, :cancellingState)
        """
    )
    protected abstract suspend fun getUnfinishedBatchCount(
        queuedState: BatchState = BatchState.Queued,
        runningState: BatchState = BatchState.Running,
        retryingState: BatchState = BatchState.Retrying,
        needsDestinationState: BatchState = BatchState.NeedsDestination,
        cancellingState: BatchState = BatchState.Cancelling
    ): Int

    @Query(
        """
        SELECT * FROM batch_items
        WHERE batchId = :batchId AND state = :state
            AND inputName IS NOT NULL AND outputName IS NOT NULL
        ORDER BY position LIMIT 1
        """
    )
    protected abstract suspend fun getNextQueuedItem(
        batchId: String,
        state: BatchItemState = BatchItemState.Queued
    ): BatchItemEntity?

    @Query(
        """
        SELECT * FROM batch_items
        WHERE batchId = :batchId AND state = :state
        ORDER BY position LIMIT 1
        """
    )
    abstract suspend fun getNextQueuedItemForPreparation(
        batchId: String,
        state: BatchItemState = BatchItemState.Queued
    ): BatchItemEntity?

    @Query(
        """
        UPDATE batch_items
        SET inputName = :inputName, outputName = :outputName, inputBytes = :inputBytes
        WHERE id = :itemId AND state IN (:queuedState, :processingState)
            AND (inputName IS NULL OR outputName IS NULL)
        """
    )
    protected abstract suspend fun setPreparedMetadata(
        itemId: String,
        inputName: String,
        outputName: String,
        inputBytes: Long,
        queuedState: BatchItemState = BatchItemState.Queued,
        processingState: BatchItemState = BatchItemState.Processing
    ): Int

    @Query("UPDATE batches SET totalInputBytes = totalInputBytes + :inputBytes WHERE id = :batchId")
    protected abstract suspend fun addPreparedInputBytes(batchId: String, inputBytes: Long)

    @Query("SELECT * FROM batch_items WHERE batchId = :batchId AND state = :state")
    abstract suspend fun getProcessingItems(
        batchId: String,
        state: BatchItemState = BatchItemState.Processing
    ): List<BatchItemEntity>

    @Query("SELECT * FROM batch_items WHERE state = :state")
    abstract suspend fun getAllProcessingItems(
        state: BatchItemState = BatchItemState.Processing
    ): List<BatchItemEntity>

    @Query(
        """
        SELECT * FROM batch_items
        WHERE (outputUri IS NOT NULL OR outputCleanupPending = 1)
            AND state IN ('Processing', 'Failed', 'Cancelled')
        """
    )
    abstract suspend fun getIncompleteOutputItems(): List<BatchItemEntity>

    @Query(
        """
        UPDATE batch_items SET outputCleanupPending = 1
        WHERE id = :itemId AND state = :state AND outputUri IS NULL
        """
    )
    abstract suspend fun markOutputCreationStarted(
        itemId: String,
        state: BatchItemState = BatchItemState.Processing
    ): Int

    @Query(
        """
        UPDATE batch_items SET width = :width, height = :height
        WHERE id = :itemId AND state = :state
            AND (width <= 0 OR height <= 0)
        """
    )
    abstract suspend fun recordDimensions(
        itemId: String,
        width: Int,
        height: Int,
        state: BatchItemState = BatchItemState.Processing
    ): Int

    @Query("UPDATE batch_items SET state = :processingState, attemptCount = attemptCount + 1 WHERE id = :itemId AND state = :queuedState")
    protected abstract suspend fun markProcessing(
        itemId: String,
        processingState: BatchItemState = BatchItemState.Processing,
        queuedState: BatchItemState = BatchItemState.Queued
    ): Int

    @Query(
        """
        UPDATE batch_items
        SET state = :queuedState, outputUri = NULL, actualOutputName = NULL,
            outputBytes = -1, savedBytes = 0, message = NULL, outputCleanupPending = 0
        WHERE id = :itemId AND state = :processingState
            AND outputUri IS NULL AND outputCleanupPending = 0
        """
    )
    protected abstract suspend fun requeueInterruptedItem(
        itemId: String,
        queuedState: BatchItemState = BatchItemState.Queued,
        processingState: BatchItemState = BatchItemState.Processing
    ): Int

    @Query(
        """
        UPDATE batch_items SET outputUri = :outputUri, actualOutputName = :actualOutputName
        WHERE id = :itemId AND state = :state
        """
    )
    abstract suspend fun recordOutputUri(
        itemId: String,
        outputUri: String,
        actualOutputName: String? = null,
        state: BatchItemState = BatchItemState.Processing
    ): Int

    @Query(
        """
        UPDATE batch_items
        SET outputUri = NULL, actualOutputName = NULL, outputBytes = -1,
            savedBytes = 0, outputCleanupPending = 0
        WHERE id = :itemId AND state IN ('Processing', 'Failed', 'Cancelled')
        """
    )
    protected abstract suspend fun clearIncompleteOutputUriRecord(itemId: String): Int

    @Query(
        """
        UPDATE batch_items
        SET state = :state, outputUri = :outputUri, actualOutputName = :actualOutputName,
            outputBytes = :outputBytes, savedBytes = :savedBytes, message = :message,
            outputCleanupPending = 0
        WHERE id = :itemId AND state = :processingState
        """
    )
    protected abstract suspend fun markTerminal(
        itemId: String,
        state: BatchItemState,
        outputUri: String?,
        actualOutputName: String?,
        outputBytes: Long,
        savedBytes: Long,
        message: String?,
        processingState: BatchItemState = BatchItemState.Processing
    ): Int

    @Query(
        """
        UPDATE batch_items
        SET state = :failedState, outputUri = NULL, actualOutputName = NULL,
            outputBytes = -1, savedBytes = 0, message = :message,
            outputCleanupPending = 0
        WHERE id = :itemId AND batchId = :batchId
            AND state IN (:queuedState, :processingState)
            AND outputUri IS NULL AND outputCleanupPending = 0
        """
    )
    protected abstract suspend fun markUnfinishedItemFailed(
        itemId: String,
        batchId: String,
        message: String,
        failedState: BatchItemState = BatchItemState.Failed,
        queuedState: BatchItemState = BatchItemState.Queued,
        processingState: BatchItemState = BatchItemState.Processing
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM batch_items
        WHERE batchId = :batchId AND state IN ('Queued', 'Processing')
        """
    )
    protected abstract suspend fun getUnfinishedItemCount(batchId: String): Int

    @Query("UPDATE batch_items SET state = :cancelledState, message = :message WHERE batchId = :batchId AND state = :queuedState")
    protected abstract suspend fun cancelQueuedItems(
        batchId: String,
        message: String,
        cancelledState: BatchItemState = BatchItemState.Cancelled,
        queuedState: BatchItemState = BatchItemState.Queued
    ): Int

    @Query("UPDATE batch_items SET state = :failedState, message = :message WHERE batchId = :batchId AND state = :queuedState")
    protected abstract suspend fun failQueuedItems(
        batchId: String,
        message: String,
        failedState: BatchItemState = BatchItemState.Failed,
        queuedState: BatchItemState = BatchItemState.Queued
    ): Int

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN state IN ('Compressed', 'CopiedOriginal', 'Failed', 'Cancelled') THEN 1 ELSE 0 END), 0) AS completedCount,
            COALESCE(SUM(CASE WHEN state = 'Compressed' THEN 1 ELSE 0 END), 0) AS compressedCount,
            COALESCE(SUM(CASE WHEN state = 'CopiedOriginal' THEN 1 ELSE 0 END), 0) AS copiedCount,
            COALESCE(SUM(CASE WHEN state = 'Failed' THEN 1 ELSE 0 END), 0) AS failedCount,
            COALESCE(SUM(CASE WHEN state = 'Cancelled' THEN 1 ELSE 0 END), 0) AS cancelledCount,
            COALESCE(SUM(CASE WHEN inputBytes > 0 THEN inputBytes ELSE 0 END), 0) AS totalInputBytes,
            COALESCE(SUM(CASE WHEN outputBytes > 0 THEN outputBytes ELSE 0 END), 0) AS totalOutputBytes,
            COALESCE(SUM(CASE
                WHEN state IN ('Compressed', 'CopiedOriginal') AND inputBytes > outputBytes AND outputBytes >= 0
                THEN inputBytes - outputBytes ELSE 0 END), 0) AS totalSavedBytes
        FROM batch_items WHERE batchId = :batchId
        """
    )
    protected abstract suspend fun getSummary(batchId: String): BatchItemSummary

    @Query(
        """
        UPDATE batches SET
            completedCount = :completedCount,
            compressedCount = :compressedCount,
            copiedCount = :copiedCount,
            failedCount = :failedCount,
            cancelledCount = :cancelledCount,
            totalInputBytes = :totalInputBytes,
            totalOutputBytes = :totalOutputBytes,
            totalSavedBytes = :totalSavedBytes
        WHERE id = :batchId
        """
    )
    protected abstract suspend fun updateSummary(
        batchId: String,
        completedCount: Int,
        compressedCount: Int,
        copiedCount: Int,
        failedCount: Int,
        cancelledCount: Int,
        totalInputBytes: Long,
        totalOutputBytes: Long,
        totalSavedBytes: Long
    )

    @Query(
        """
        UPDATE batches SET
            completedCount = completedCount + :completedDelta,
            compressedCount = compressedCount + :compressedDelta,
            copiedCount = copiedCount + :copiedDelta,
            failedCount = failedCount + :failedDelta,
            cancelledCount = cancelledCount + :cancelledDelta,
            totalOutputBytes = totalOutputBytes + :outputBytesDelta,
            totalSavedBytes = totalSavedBytes + :savedBytesDelta
        WHERE id = :batchId
        """
    )
    protected abstract suspend fun addSummaryDelta(
        batchId: String,
        completedDelta: Int = 0,
        compressedDelta: Int = 0,
        copiedDelta: Int = 0,
        failedDelta: Int = 0,
        cancelledDelta: Int = 0,
        outputBytesDelta: Long = 0L,
        savedBytesDelta: Long = 0L
    )

    @Query(
        """
        UPDATE batches SET state = :runningState, lastError = NULL
        WHERE id = :batchId AND state IN (:queuedState, :retryingState)
        """
    )
    abstract suspend fun markBatchRunning(
        batchId: String,
        runningState: BatchState = BatchState.Running,
        queuedState: BatchState = BatchState.Queued,
        retryingState: BatchState = BatchState.Retrying
    )

    @Query(
        """
        UPDATE batches SET state = :retryingState, lastError = :error
        WHERE id = :batchId AND state IN (
            :queuedState, :runningState, :alreadyRetryingState, :cancellingState
        )
        """
    )
    protected abstract suspend fun markBatchRetrying(
        batchId: String,
        error: String,
        retryingState: BatchState = BatchState.Retrying,
        queuedState: BatchState = BatchState.Queued,
        runningState: BatchState = BatchState.Running,
        alreadyRetryingState: BatchState = BatchState.Retrying,
        cancellingState: BatchState = BatchState.Cancelling
    ): Int

    @Query(
        """
        UPDATE batches SET state = :needsDestinationState, lastError = :error
        WHERE id = :batchId AND state IN (:queuedState, :runningState, :retryingState)
        """
    )
    abstract suspend fun markNeedsDestination(
        batchId: String,
        error: String,
        needsDestinationState: BatchState = BatchState.NeedsDestination,
        queuedState: BatchState = BatchState.Queued,
        runningState: BatchState = BatchState.Running,
        retryingState: BatchState = BatchState.Retrying
    ): Int

    @Query(
        """
        UPDATE batches SET lastError = :error
        WHERE id = :batchId AND state IN (
            :queuedState, :runningState, :retryingState, :cancellingState
        )
        """
    )
    abstract suspend fun recordActiveError(
        batchId: String,
        error: String,
        queuedState: BatchState = BatchState.Queued,
        runningState: BatchState = BatchState.Running,
        retryingState: BatchState = BatchState.Retrying,
        cancellingState: BatchState = BatchState.Cancelling
    ): Int

    @Query(
        """
        UPDATE batches SET destinationUri = :destinationUri, state = :queuedState, lastError = NULL
        WHERE id = :batchId AND state = :needsDestinationState
        """
    )
    abstract suspend fun replaceDestination(
        batchId: String,
        destinationUri: String,
        queuedState: BatchState = BatchState.Queued,
        needsDestinationState: BatchState = BatchState.NeedsDestination
    ): Int

    @Query(
        """
        UPDATE batches SET state = :cancellingState, cancellationRequested = 1
        WHERE id = :batchId AND state IN (
            :queuedState, :runningState, :retryingState, :needsDestinationState, :alreadyCancellingState
        )
        """
    )
    protected abstract suspend fun markCancellationRequested(
        batchId: String,
        cancellingState: BatchState = BatchState.Cancelling,
        queuedState: BatchState = BatchState.Queued,
        runningState: BatchState = BatchState.Running,
        retryingState: BatchState = BatchState.Retrying,
        needsDestinationState: BatchState = BatchState.NeedsDestination,
        alreadyCancellingState: BatchState = BatchState.Cancelling
    ): Int

    @Query(
        """
        UPDATE batches SET state = :state, completedAt = :completedAt, lastError = :error
        WHERE id = :batchId AND state IN (
            :queuedState, :runningState, :retryingState, :needsDestinationState, :cancellingState
        )
        """
    )
    protected abstract suspend fun markBatchTerminal(
        batchId: String,
        state: BatchState,
        completedAt: Long,
        error: String?,
        queuedState: BatchState = BatchState.Queued,
        runningState: BatchState = BatchState.Running,
        retryingState: BatchState = BatchState.Retrying,
        needsDestinationState: BatchState = BatchState.NeedsDestination,
        cancellingState: BatchState = BatchState.Cancelling
    ): Int

    @Transaction
    open suspend fun createBatch(batch: BatchEntity, items: List<BatchItemEntity>) {
        check(getUnfinishedBatchCount() == 0) {
            "A batch is already active. Wait for it to finish or cancel it before starting another."
        }
        insertBatch(batch)
        insertItems(items)
        refreshSummary(batch.id)
    }

    @Transaction
    open suspend fun prepareItem(
        itemId: String,
        batchId: String,
        inputName: String,
        outputName: String,
        inputBytes: Long
    ): Boolean {
        val changed = setPreparedMetadata(itemId, inputName, outputName, inputBytes) == 1
        if (changed && inputBytes > 0L) addPreparedInputBytes(batchId, inputBytes)
        return changed
    }

    @Transaction
    open suspend fun claimNextItem(batchId: String): BatchItemEntity? {
        if (getProcessingItems(batchId).size >= MAX_CONCURRENT_IMAGE_PROCESSORS) return null
        while (true) {
            val item = getNextQueuedItem(batchId) ?: return null
            if (markProcessing(item.id) == 1) {
                return item.copy(state = BatchItemState.Processing, attemptCount = item.attemptCount + 1)
            }
        }
    }

    @Transaction
    open suspend fun completeItem(
        itemId: String,
        batchId: String,
        state: BatchItemState,
        outputUri: String?,
        actualOutputName: String? = null,
        outputBytes: Long,
        message: String?
    ): Boolean {
        require(state.isTerminal)
        val item = getItem(itemId)
            ?.takeIf { item -> item.batchId == batchId && item.state == BatchItemState.Processing }
            ?: return false
        val savedBytes = savedBytesFor(item, state, outputBytes)
        val changed = markTerminal(
            itemId = itemId,
            state = state,
            outputUri = outputUri,
            actualOutputName = actualOutputName,
            outputBytes = outputBytes,
            savedBytes = savedBytes,
            message = message
        ) == 1
        if (changed) addTerminalSummary(batchId, item, state, outputBytes)
        return changed
    }

    @Transaction
    open suspend fun failUnfinishedItem(itemId: String, batchId: String, message: String): Boolean {
        val item = getItem(itemId)
            ?.takeIf { item ->
                item.batchId == batchId &&
                    (item.state == BatchItemState.Queued || item.state == BatchItemState.Processing) &&
                    item.outputUri == null && !item.outputCleanupPending
            }
            ?: return false
        val changed = markUnfinishedItemFailed(itemId, batchId, message) == 1
        if (changed) addTerminalSummary(batchId, item, BatchItemState.Failed, -1L)
        return changed
    }

    @Transaction
    open suspend fun clearIncompleteOutputUri(itemId: String, batchId: String): Boolean {
        val changed = clearIncompleteOutputUriRecord(itemId) == 1
        if (changed) refreshSummary(batchId)
        return changed
    }

    @Transaction
    open suspend fun requeueOrFailProcessingItem(
        itemId: String,
        batchId: String,
        maxAttempts: Int,
        message: String
    ): Boolean {
        val item = getProcessingItems(batchId).firstOrNull { it.id == itemId } ?: return false
        val batch = getBatch(batchId) ?: return false
        if (batch.cancellationRequested) {
            val changed = markTerminal(
                itemId = item.id,
                state = BatchItemState.Cancelled,
                outputUri = null,
                actualOutputName = null,
                outputBytes = -1L,
                savedBytes = 0L,
                message = "Cancelled by user."
            ) == 1
            if (changed) {
                addTerminalSummary(batchId, item, BatchItemState.Cancelled, -1L)
                if (getProcessingItems(batchId).isEmpty()) {
                    markBatchTerminal(batchId, BatchState.Cancelled, System.currentTimeMillis(), null)
                }
            }
            return changed
        }
        val changed = if (item.attemptCount < maxAttempts) {
            requeueInterruptedItem(item.id) == 1
        } else {
            val terminal = markTerminal(
                itemId = item.id,
                state = BatchItemState.Failed,
                outputUri = null,
                actualOutputName = null,
                outputBytes = -1L,
                savedBytes = 0L,
                message = message
            ) == 1
            if (terminal) addTerminalSummary(batchId, item, BatchItemState.Failed, -1L)
            terminal
        }
        return changed
    }

    @Transaction
    open suspend fun recoverInterruptedItems(
        batchId: String,
        maxAttempts: Int,
        blockedItemIds: Set<String> = emptySet()
    ): Int {
        val batch = getBatch(batchId) ?: return 0
        val interrupted = getProcessingItems(batchId).filterNot { item -> item.id in blockedItemIds }
        interrupted.forEach { item ->
            when {
                batch.cancellationRequested -> {
                    val changed = markTerminal(
                        itemId = item.id,
                        state = BatchItemState.Cancelled,
                        outputUri = null,
                        actualOutputName = null,
                        outputBytes = -1L,
                        savedBytes = 0L,
                        message = "Cancelled by user."
                    ) == 1
                    if (changed) addTerminalSummary(batchId, item, BatchItemState.Cancelled, -1L)
                }

                item.attemptCount < maxAttempts -> requeueInterruptedItem(item.id)

                else -> {
                    val changed = markTerminal(
                        itemId = item.id,
                        state = BatchItemState.Failed,
                        outputUri = null,
                        actualOutputName = null,
                        outputBytes = -1L,
                        savedBytes = 0L,
                        message = "Processing was interrupted repeatedly."
                    ) == 1
                    if (changed) addTerminalSummary(batchId, item, BatchItemState.Failed, -1L)
                }
            }
        }
        return interrupted.size
    }

    suspend fun markForWorkerRetry(batchId: String, error: String): Boolean {
        return markBatchRetrying(batchId, error) == 1
    }

    @Transaction
    open suspend fun requestCancellation(batchId: String): Boolean {
        if (markCancellationRequested(batchId) == 0) return false
        val cancelledCount = cancelQueuedItems(batchId, "Cancelled by user.")
        if (cancelledCount > 0) {
            addSummaryDelta(
                batchId = batchId,
                completedDelta = cancelledCount,
                cancelledDelta = cancelledCount
            )
        }
        if (getProcessingItems(batchId).isEmpty()) {
            markBatchTerminal(batchId, BatchState.Cancelled, System.currentTimeMillis(), null)
        }
        return true
    }

    @Transaction
    open suspend fun finishBatch(batchId: String) {
        refreshSummary(batchId)
        val batch = getBatch(batchId) ?: return
        if (batch.state.isTerminal || getUnfinishedItemCount(batchId) > 0) return
        val state = when {
            batch.cancellationRequested -> BatchState.Cancelled
            batch.failedCount > 0 -> BatchState.CompletedWithFailures
            else -> BatchState.Completed
        }
        markBatchTerminal(batchId, state, System.currentTimeMillis(), null)
    }

    @Transaction
    open suspend fun failBatch(batchId: String, error: String) {
        val batch = getBatch(batchId) ?: return
        if (batch.state.isTerminal) return

        if (batch.cancellationRequested) {
            getProcessingItems(batchId).forEach { item ->
                val changed = markTerminal(
                    itemId = item.id,
                    state = BatchItemState.Cancelled,
                    outputUri = null,
                    actualOutputName = null,
                    outputBytes = -1L,
                    savedBytes = 0L,
                    message = "Cancelled by user."
                ) == 1
                if (changed) addTerminalSummary(batchId, item, BatchItemState.Cancelled, -1L)
            }
            val cancelledCount = cancelQueuedItems(batchId, "Cancelled by user.")
            if (cancelledCount > 0) {
                addSummaryDelta(
                    batchId = batchId,
                    completedDelta = cancelledCount,
                    cancelledDelta = cancelledCount
                )
            }
            markBatchTerminal(batchId, BatchState.Cancelled, System.currentTimeMillis(), null)
            return
        }

        getProcessingItems(batchId).forEach { item ->
            val changed = markTerminal(
                itemId = item.id,
                state = BatchItemState.Failed,
                outputUri = null,
                actualOutputName = null,
                outputBytes = -1L,
                savedBytes = 0L,
                message = error
            ) == 1
            if (changed) addTerminalSummary(batchId, item, BatchItemState.Failed, -1L)
        }
        val failedCount = failQueuedItems(batchId, "Batch stopped: $error")
        if (failedCount > 0) {
            addSummaryDelta(
                batchId = batchId,
                completedDelta = failedCount,
                failedDelta = failedCount
            )
        }
        markBatchTerminal(batchId, BatchState.Failed, System.currentTimeMillis(), error)
    }

    private suspend fun addTerminalSummary(
        batchId: String,
        item: BatchItemEntity,
        state: BatchItemState,
        outputBytes: Long
    ) {
        val persistedOutputBytes = outputBytes.takeIf { bytes -> bytes > 0L } ?: 0L
        val savedBytes = savedBytesFor(item, state, outputBytes)
        addSummaryDelta(
            batchId = batchId,
            completedDelta = 1,
            compressedDelta = if (state == BatchItemState.Compressed) 1 else 0,
            copiedDelta = if (state == BatchItemState.CopiedOriginal) 1 else 0,
            failedDelta = if (state == BatchItemState.Failed) 1 else 0,
            cancelledDelta = if (state == BatchItemState.Cancelled) 1 else 0,
            outputBytesDelta = persistedOutputBytes,
            savedBytesDelta = savedBytes
        )
    }

    private fun savedBytesFor(item: BatchItemEntity, state: BatchItemState, outputBytes: Long): Long {
        return if (state == BatchItemState.Compressed || state == BatchItemState.CopiedOriginal) {
            (item.inputBytes - outputBytes).takeIf { saved -> outputBytes >= 0L && saved > 0L } ?: 0L
        } else {
            0L
        }
    }

    private suspend fun refreshSummary(batchId: String) {
        val summary = getSummary(batchId)
        updateSummary(
            batchId = batchId,
            completedCount = summary.completedCount,
            compressedCount = summary.compressedCount,
            copiedCount = summary.copiedCount,
            failedCount = summary.failedCount,
            cancelledCount = summary.cancelledCount,
            totalInputBytes = summary.totalInputBytes,
            totalOutputBytes = summary.totalOutputBytes,
            totalSavedBytes = summary.totalSavedBytes
        )
    }
}
