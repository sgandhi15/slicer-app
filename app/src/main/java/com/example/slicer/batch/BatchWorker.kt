package com.example.slicer.batch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.slicer.MainActivity
import com.example.slicer.R
import com.example.slicer.SlicerApplication
import com.example.slicer.core.LOG_TAG
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.processing.processBatchItem
import com.example.slicer.storage.createOutputTempFile
import com.example.slicer.storage.deleteOutputTempFile
import com.example.slicer.storage.OutputPublicationException
import com.example.slicer.storage.OutputFolderAccess
import com.example.slicer.storage.outputFolderAccess
import com.example.slicer.storage.publishPreparedOutput
import com.example.slicer.storage.queryExistingOutputNames
import com.example.slicer.storage.resolveContentSize
import com.example.slicer.storage.resolveDisplayName
import com.example.slicer.storage.sanitizeOutputFileName
import com.example.slicer.storage.uniqueOutputName
import com.example.slicer.storage.withOutputLifecycleLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

internal class BatchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val application = appContext.applicationContext as SlicerApplication
    private val dao = application.database.batchDao()
    private val repository = application.batchRepository

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(BATCH_ID_KEY) ?: return Result.failure()
        return withOutputLifecycleLock {
            try {
                if (runAttemptCount > MAX_WORKER_RETRIES) {
                    val blockedItems = cleanupInterruptedBatch(batchId)
                    if (blockedItems.isEmpty()) {
                        dao.failBatch(batchId, "Batch processing was interrupted repeatedly.")
                    } else {
                        markCleanupBlocked(batchId)
                    }
                    return@withOutputLifecycleLock Result.failure()
                }
                processBatch(batchId)
                Result.success()
            } catch (_: ItemRetryScheduledException) {
                Result.success()
            } catch (_: CleanupBlockedException) {
                if (runAttemptCount < MAX_WORKER_RETRIES) Result.retry() else Result.failure()
            } catch (cancelled: CancellationException) {
                persistInterruption(batchId, "Batch processing was interrupted.")
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: "Batch processing was interrupted."
                if (runAttemptCount < MAX_WORKER_RETRIES) {
                    persistInterruption(batchId, message)
                    Result.retry()
                } else {
                    withContext(NonCancellable) {
                        val blockedItems = cleanupInterruptedBatch(batchId)
                        if (blockedItems.isEmpty()) {
                            dao.failBatch(batchId, message)
                        } else {
                            markCleanupBlocked(batchId)
                        }
                    }
                    Result.failure()
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val batch = inputData.getString(BATCH_ID_KEY)?.let { dao.getBatch(it) }
        return createForegroundInfo(batch, null)
    }

    private suspend fun processBatch(batchId: String) {
        var batch = dao.getBatch(batchId) ?: return
        if (!batch.state.isActive) return

        setForeground(createForegroundInfo(batch, null))
        val cleanup = repository.cleanupAbandonedArtifacts()
        dao.recoverInterruptedItems(
            batchId = batchId,
            maxAttempts = MAX_ITEM_ATTEMPTS,
            blockedItemIds = cleanup.blockedProcessingItemIds
        )
        if (cleanup.blockedProcessingItemIds.isNotEmpty()) {
            markCleanupBlocked(batchId)
            throw CleanupBlockedException()
        }

        batch = dao.getBatch(batchId) ?: return
        if (batch.cancellationRequested) {
            dao.requestCancellation(batchId)
            dao.finishBatch(batchId)
            return
        }
        if (!ensureDestinationAvailable(batch)) return
        if (!prepareQueuedItems(batch)) return

        batch = dao.getBatch(batchId) ?: return
        if (batch.cancellationRequested) {
            dao.requestCancellation(batchId)
            dao.finishBatch(batchId)
            return
        }
        dao.markBatchRunning(batchId)

        while (true) {
            batch = dao.getBatch(batchId) ?: return
            if (batch.cancellationRequested) {
                dao.requestCancellation(batchId)
                dao.finishBatch(batchId)
                return
            }

            if (!ensureDestinationAvailable(batch)) return

            val item = dao.claimNextItem(batchId) ?: break
            val inputName = checkNotNull(item.inputName) { "Queued item is missing its input name." }
            val outputName = checkNotNull(item.outputName) { "Queued item is missing its output name." }
            setForeground(createForegroundInfo(batch, inputName))

            try {
                val tempFile = applicationContext.createOutputTempFile(item.id)
                val prepared = processBatchItem(
                    context = applicationContext,
                    inputUri = item.sourceUri.toUri(),
                    tempFile = tempFile,
                    quality = batch.quality,
                    inputBytes = item.inputBytes
                )
                val published = applicationContext.publishPreparedOutput(
                    tempFile = tempFile,
                    outputFolderUri = batch.destinationUri.toUri(),
                    outputName = outputName,
                    itemId = item.id,
                    onOutputCreated = { outputUri, actualOutputName ->
                        check(
                            dao.recordOutputUri(
                                itemId = item.id,
                                outputUri = outputUri.toString(),
                                actualOutputName = actualOutputName
                            ) == 1
                        ) {
                            "Could not journal the provisional output."
                        }
                    }
                )
                check(
                    dao.completeItem(
                        itemId = item.id,
                        batchId = batchId,
                        state = prepared.decision.toItemState(),
                        outputUri = published.uri.toString(),
                        actualOutputName = published.actualName,
                        outputBytes = published.outputBytes,
                        message = prepared.message
                    )
                ) {
                    "Could not persist the published output result."
                }
                if (!applicationContext.deleteOutputTempFile(item.id)) {
                    Log.w(LOG_TAG, "Could not remove temporary output for item ${item.id}.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                handleItemFailure(item, error)
            }

            val updated = dao.getBatch(batchId) ?: return
            setForeground(createForegroundInfo(updated, null))
        }

        dao.finishBatch(batchId)
    }

    private suspend fun handleItemFailure(item: BatchItemEntity, error: Exception) {
        val cleanup = withContext(NonCancellable) {
            if (error is OutputPublicationException &&
                !error.cleanupSucceeded && error.incompleteUri != null &&
                dao.getItem(item.id)?.state == BatchItemState.Processing
            ) {
                dao.recordOutputUri(
                    itemId = item.id,
                    outputUri = error.incompleteUri.toString(),
                    actualOutputName = applicationContext.resolveDisplayName(error.incompleteUri)
                )
            }
            repository.cleanupAbandonedArtifacts()
        }
        if (item.id in cleanup.blockedProcessingItemIds) {
            markCleanupBlocked(item.batchId)
            throw CleanupBlockedException()
        }
        val current = dao.getItem(item.id) ?: return
        if (current.state != BatchItemState.Processing) return

        val message = error.message ?: "Image processing failed."
        if (error.isRetryableItemError()) {
            dao.requeueOrFailProcessingItem(
                itemId = current.id,
                batchId = current.batchId,
                maxAttempts = MAX_ITEM_ATTEMPTS,
                message = "$message The image could not be processed after $MAX_ITEM_ATTEMPTS attempts."
            )
            if (dao.getItem(current.id)?.state == BatchItemState.Queued) {
                if (dao.markForWorkerRetry(current.batchId, message)) {
                    repository.scheduleItemRetry(current.batchId, current.attemptCount)
                    throw ItemRetryScheduledException()
                }
            }
            return
        }

        dao.completeItem(
            itemId = current.id,
            batchId = current.batchId,
            state = BatchItemState.Failed,
            outputUri = null,
            actualOutputName = null,
            outputBytes = -1L,
            message = message
        )
    }

    private suspend fun cleanupInterruptedBatch(batchId: String): Set<String> {
        val cleanup = repository.cleanupAbandonedArtifacts()
        dao.recoverInterruptedItems(
            batchId = batchId,
            maxAttempts = MAX_ITEM_ATTEMPTS,
            blockedItemIds = cleanup.blockedProcessingItemIds
        )
        return cleanup.blockedProcessingItemIds
    }

    private suspend fun persistInterruption(batchId: String, message: String) = withContext(NonCancellable) {
        if (dao.getBatch(batchId)?.state?.isActive != true) return@withContext

        val blockedItems = cleanupInterruptedBatch(batchId)
        if (blockedItems.isNotEmpty()) {
            markCleanupBlocked(batchId)
            return@withContext
        }
        val batch = dao.getBatch(batchId) ?: return@withContext
        if (batch.cancellationRequested) {
            dao.requestCancellation(batchId)
            dao.finishBatch(batchId)
        } else if (batch.state.isActive) {
            dao.markForWorkerRetry(batchId, message)
        }
    }

    private class ItemRetryScheduledException : Exception()

    private class CleanupBlockedException : Exception()

    private suspend fun markCleanupBlocked(batchId: String) {
        val message = "Waiting to remove an incomplete output before retrying."
        dao.markForWorkerRetry(batchId, message)
        dao.recordActiveError(batchId, message)
    }

    private fun Exception.isRetryableItemError(): Boolean {
        return this is IOException || this is SecurityException
    }

    private suspend fun prepareQueuedItems(batch: BatchEntity): Boolean {
        val items = dao.getItems(batch.id)
        val usedNames = applicationContext.queryExistingOutputNames(batch.destinationUri.toUri()).toMutableSet()
        items.mapNotNullTo(usedNames) { item -> item.outputName }

        for (item in items) {
            if (item.state != BatchItemState.Queued || (item.inputName != null && item.outputName != null)) continue

            val currentBatch = dao.getBatch(batch.id) ?: return false
            if (currentBatch.cancellationRequested) {
                dao.requestCancellation(batch.id)
                dao.finishBatch(batch.id)
                return false
            }

            setForeground(
                createForegroundInfo(
                    currentBatch,
                    "Preparing image ${item.position + 1} of ${currentBatch.totalCount}"
                )
            )
            val sourceUri = item.sourceUri.toUri()
            val inputName = applicationContext.resolveDisplayName(sourceUri)
            val outputName = applicationContext.uniqueOutputName(
                sanitizeOutputFileName(inputName),
                usedNames
            )
            dao.prepareItem(
                itemId = item.id,
                batchId = batch.id,
                inputName = inputName,
                outputName = outputName,
                inputBytes = applicationContext.resolveContentSize(sourceUri)
            )
        }
        check(
            dao.getItems(batch.id).none { item ->
                item.state == BatchItemState.Queued && (item.inputName == null || item.outputName == null)
            }
        ) { "Batch item preparation did not finish." }
        return true
    }

    private suspend fun ensureDestinationAvailable(batch: BatchEntity): Boolean {
        return when (applicationContext.outputFolderAccess(batch.destinationUri.toUri())) {
            OutputFolderAccess.Writable -> true
            OutputFolderAccess.PermissionMissing -> {
                dao.markNeedsDestination(
                    batch.id,
                    "Output folder is no longer accessible. Choose a new folder to continue."
                )
                false
            }

            OutputFolderAccess.TemporarilyUnavailable -> {
                throw IOException("Output provider is temporarily unavailable.")
            }
        }
    }

    private fun createForegroundInfo(batch: BatchEntity?, currentFile: String?): ForegroundInfo {
        createNotificationChannel()
        val completed = batch?.completedCount ?: 0
        val total = batch?.totalCount ?: 0
        val text = when {
            currentFile != null -> currentFile
            total > 0 -> "$completed of $total images processed"
            else -> "Preparing image batch"
        }
        val launchIntent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Slicer is compressing images")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, completed, total <= 0)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            foregroundServiceType()
        )
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
    }

    private fun createNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Image compression",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun ProcessingDecision.toItemState(): BatchItemState = when (this) {
        ProcessingDecision.Compressed -> BatchItemState.Compressed
        ProcessingDecision.CopiedOriginal -> BatchItemState.CopiedOriginal
        ProcessingDecision.Failed -> BatchItemState.Failed
        ProcessingDecision.Cancelled -> BatchItemState.Cancelled
    }

    companion object {
        const val BATCH_ID_KEY = "batch_id"
        private const val NOTIFICATION_CHANNEL_ID = "slicer_processing"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_WORKER_RETRIES = 2
        private const val MAX_ITEM_ATTEMPTS = MAX_WORKER_RETRIES + 1
    }
}
