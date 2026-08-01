package com.example.slicer.batch

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT * FROM batch_items WHERE batchId = :batchId ORDER BY position")
    abstract fun observeItems(batchId: String): Flow<List<BatchItemEntity>>

    @Query("SELECT * FROM batches WHERE id = :batchId")
    abstract suspend fun getBatch(batchId: String): BatchEntity?

    @Query("SELECT * FROM batch_items WHERE batchId = :batchId ORDER BY position")
    abstract suspend fun getItems(batchId: String): List<BatchItemEntity>

    @Query("SELECT * FROM batch_items WHERE id = :itemId")
    abstract suspend fun getItem(itemId: String): BatchItemEntity?

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
        UPDATE batch_items
        SET inputName = :inputName, outputName = :outputName, inputBytes = :inputBytes
        WHERE id = :itemId AND state = :queuedState
            AND (inputName IS NULL OR outputName IS NULL)
        """
    )
    protected abstract suspend fun setPreparedMetadata(
        itemId: String,
        inputName: String,
        outputName: String,
        inputBytes: Long,
        queuedState: BatchItemState = BatchItemState.Queued
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
        WHERE outputUri IS NOT NULL AND state IN ('Processing', 'Failed', 'Cancelled')
        """
    )
    abstract suspend fun getIncompleteOutputItems(): List<BatchItemEntity>

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
            outputBytes = -1, message = NULL
        WHERE id = :itemId AND state = :processingState
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
        SET outputUri = NULL, actualOutputName = NULL, outputBytes = -1
        WHERE id = :itemId AND state IN ('Processing', 'Failed', 'Cancelled')
        """
    )
    protected abstract suspend fun clearIncompleteOutputUriRecord(itemId: String): Int

    @Query(
        """
        UPDATE batch_items
        SET state = :state, outputUri = :outputUri, actualOutputName = :actualOutputName,
            outputBytes = :outputBytes, message = :message
        WHERE id = :itemId AND state = :processingState
        """
    )
    protected abstract suspend fun markTerminal(
        itemId: String,
        state: BatchItemState,
        outputUri: String?,
        actualOutputName: String?,
        outputBytes: Long,
        message: String?,
        processingState: BatchItemState = BatchItemState.Processing
    ): Int

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
        if (getProcessingItems(batchId).isNotEmpty()) return null
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
        val changed = markTerminal(itemId, state, outputUri, actualOutputName, outputBytes, message) == 1
        if (changed) refreshSummary(batchId)
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
                message = "Cancelled by user."
            ) == 1
            if (changed) {
                refreshSummary(batchId)
                if (getProcessingItems(batchId).isEmpty()) {
                    markBatchTerminal(batchId, BatchState.Cancelled, System.currentTimeMillis(), null)
                }
            }
            return changed
        }
        val changed = if (item.attemptCount < maxAttempts) {
            requeueInterruptedItem(item.id) == 1
        } else {
            markTerminal(
                itemId = item.id,
                state = BatchItemState.Failed,
                outputUri = null,
                actualOutputName = null,
                outputBytes = -1L,
                message = message
            ) == 1
        }
        if (changed) refreshSummary(batchId)
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
                batch.cancellationRequested -> markTerminal(
                    itemId = item.id,
                    state = BatchItemState.Cancelled,
                    outputUri = null,
                    actualOutputName = null,
                    outputBytes = -1L,
                    message = "Cancelled by user."
                )

                item.attemptCount < maxAttempts -> requeueInterruptedItem(item.id)

                else -> markTerminal(
                    itemId = item.id,
                    state = BatchItemState.Failed,
                    outputUri = null,
                    actualOutputName = null,
                    outputBytes = -1L,
                    message = "Processing was interrupted repeatedly."
                )
            }
        }
        if (interrupted.isNotEmpty()) refreshSummary(batchId)
        return interrupted.size
    }

    suspend fun markForWorkerRetry(batchId: String, error: String): Boolean {
        return markBatchRetrying(batchId, error) == 1
    }

    @Transaction
    open suspend fun requestCancellation(batchId: String): Boolean {
        if (markCancellationRequested(batchId) == 0) return false
        cancelQueuedItems(batchId, "Cancelled by user.")
        refreshSummary(batchId)
        if (getProcessingItems(batchId).isEmpty()) {
            markBatchTerminal(batchId, BatchState.Cancelled, System.currentTimeMillis(), null)
        }
        return true
    }

    @Transaction
    open suspend fun finishBatch(batchId: String) {
        refreshSummary(batchId)
        val batch = getBatch(batchId) ?: return
        if (!batch.state.isActive) return
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
        if (!batch.state.isActive) return

        if (batch.cancellationRequested) {
            getProcessingItems(batchId).forEach { item ->
                markTerminal(
                    itemId = item.id,
                    state = BatchItemState.Cancelled,
                    outputUri = null,
                    actualOutputName = null,
                    outputBytes = -1L,
                    message = "Cancelled by user."
                )
            }
            cancelQueuedItems(batchId, "Cancelled by user.")
            refreshSummary(batchId)
            markBatchTerminal(batchId, BatchState.Cancelled, System.currentTimeMillis(), null)
            return
        }

        getProcessingItems(batchId).forEach { item ->
            markTerminal(
                itemId = item.id,
                state = BatchItemState.Failed,
                outputUri = null,
                actualOutputName = null,
                outputBytes = -1L,
                message = error
            )
        }
        failQueuedItems(batchId, "Batch stopped: $error")
        refreshSummary(batchId)
        markBatchTerminal(batchId, BatchState.Failed, System.currentTimeMillis(), error)
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
