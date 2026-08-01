package com.example.slicer.batch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.slicer.MainActivity
import com.example.slicer.R
import com.example.slicer.SlicerApplication
import com.example.slicer.core.BatchFileResult
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.processing.processBatchItem
import com.example.slicer.storage.canWriteToOutputFolder
import com.example.slicer.storage.queryExistingOutputNames
import com.example.slicer.storage.resolveContentSize
import com.example.slicer.storage.resolveDisplayName
import com.example.slicer.storage.sanitizeOutputFileName
import com.example.slicer.storage.uniqueOutputName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

internal class BatchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val dao = (appContext.applicationContext as SlicerApplication).database.batchDao()

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(BATCH_ID_KEY) ?: return Result.failure()
        return try {
            if (runAttemptCount > MAX_WORKER_RETRIES) {
                dao.failBatch(batchId, "Batch processing was interrupted repeatedly.")
                return Result.failure()
            }
            processBatch(batchId)
            Result.success()
        } catch (cancelled: CancellationException) {
            persistInterruption(batchId)
            throw cancelled
        } catch (error: Exception) {
            if (runAttemptCount < MAX_WORKER_RETRIES) {
                val message = error.message ?: "Batch processing was interrupted."
                dao.prepareForWorkerRetry(batchId, MAX_ITEM_ATTEMPTS, message)
                Result.retry()
            } else {
                val message = error.message ?: "Batch processing failed."
                dao.failBatch(batchId, message)
                Result.failure()
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
        dao.recoverInterruptedItems(batchId, MAX_ITEM_ATTEMPTS)

        batch = dao.getBatch(batchId) ?: return
        if (batch.cancellationRequested) {
            dao.requestCancellation(batchId)
            dao.finishBatch(batchId)
            return
        }
        if (!applicationContext.canWriteToOutputFolder(batch.destinationUri.toUri())) {
            dao.markNeedsDestination(batchId, "Output folder is no longer accessible. Choose a new folder to continue.")
            return
        }
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

            if (!applicationContext.canWriteToOutputFolder(batch.destinationUri.toUri())) {
                dao.markNeedsDestination(batchId, "Output folder is no longer accessible. Choose a new folder to continue.")
                return
            }

            val item = dao.claimNextItem(batchId) ?: break
            val inputName = checkNotNull(item.inputName) { "Queued item is missing its input name." }
            val outputName = checkNotNull(item.outputName) { "Queued item is missing its output name." }
            setForeground(createForegroundInfo(batch, inputName))

            var createdOutputUri = item.outputUri
            val result = try {
                processBatchItem(
                    context = applicationContext,
                    inputUri = item.sourceUri.toUri(),
                    inputName = inputName,
                    outputFolderUri = batch.destinationUri.toUri(),
                    outputName = outputName,
                    existingOutputUri = item.outputUri?.toUri(),
                    quality = batch.quality,
                    inputBytes = item.inputBytes,
                    onOutputCreated = { outputUri ->
                        createdOutputUri = outputUri.toString()
                        dao.recordOutputUri(
                            itemId = item.id,
                            outputUri = createdOutputUri,
                            actualOutputName = applicationContext.resolveDisplayName(outputUri)
                        )
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error.isTransientProviderError()) {
                    dao.requeueOrFailProcessingItem(
                        itemId = item.id,
                        batchId = batchId,
                        maxAttempts = MAX_ITEM_ATTEMPTS,
                        message = error.message ?: "Image processing failed repeatedly."
                    )
                    continue
                }
                BatchFileResult(
                    inputUri = item.sourceUri.toUri(),
                    inputName = inputName,
                    outputUri = createdOutputUri?.toUri(),
                    decision = ProcessingDecision.Failed,
                    inputBytes = item.inputBytes,
                    outputBytes = -1L,
                    message = error.message ?: "Image processing failed."
                )
            }

            dao.completeItem(
                itemId = item.id,
                batchId = batchId,
                state = result.decision.toItemState(),
                outputUri = result.outputUri?.toString(),
                actualOutputName = result.outputUri?.let(applicationContext::resolveDisplayName),
                outputBytes = result.outputBytes,
                message = result.message
            )

            val updated = dao.getBatch(batchId) ?: return
            setForeground(createForegroundInfo(updated, null))
        }

        dao.finishBatch(batchId)
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

    private suspend fun persistInterruption(batchId: String) = withContext(NonCancellable) {
        if (dao.getBatch(batchId)?.state?.isActive == true) {
            dao.prepareForWorkerRetry(
                batchId = batchId,
                maxAttempts = MAX_ITEM_ATTEMPTS,
                error = "Batch processing was interrupted."
            )
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

    private fun Exception.isTransientProviderError(): Boolean {
        return this is IOException || this is SecurityException
    }

    companion object {
        const val BATCH_ID_KEY = "batch_id"
        private const val NOTIFICATION_CHANNEL_ID = "slicer_processing"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_WORKER_RETRIES = 2
        private const val MAX_ITEM_ATTEMPTS = MAX_WORKER_RETRIES + 1
    }
}
