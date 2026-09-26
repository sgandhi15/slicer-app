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
import com.example.slicer.core.RetryableCleanupException
import com.example.slicer.processing.processBatchItem
import com.example.slicer.processing.JpegStatePersistenceException
import com.example.slicer.storage.createOutputTempFile
import com.example.slicer.storage.deleteOutputTempFile
import com.example.slicer.storage.OutputPublicationException
import com.example.slicer.storage.OutputFolderAccess
import com.example.slicer.storage.outputFolderAccess
import com.example.slicer.storage.publishPreparedOutput
import com.example.slicer.storage.resolveContentSize
import com.example.slicer.storage.resolveDisplayName
import com.example.slicer.storage.sanitizeOutputFileName
import com.example.slicer.storage.withOutputLifecycleLock
import com.example.slicer.telemetry.ProcessingMetricsRecorder
import com.example.slicer.telemetry.ProcessingStage
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
                    preserveForManualRecovery(batchId, "Batch processing was interrupted repeatedly.")
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
                if (error is RetryableWorkerFailure && runAttemptCount < MAX_WORKER_RETRIES) {
                    persistInterruption(batchId, message)
                    Result.retry()
                } else {
                    withContext(NonCancellable) {
                        preserveForManualRecovery(batchId, message)
                    }
                    Result.failure()
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        repository.reconcileSourcePermissions()
                    } catch (error: Exception) {
                        Log.w(LOG_TAG, "Could not reconcile source permissions after worker execution.", error)
                    }
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
        if (!cleanup.temporaryOutputsCleaned || cleanup.blockedProcessingItemIds.isNotEmpty()) {
            markCleanupBlocked(batchId)
            throw CleanupBlockedException()
        }
        repository.reconcileRequiredSourcePermissions()

        batch = dao.getBatch(batchId) ?: return
        if (!batch.state.isActive) return
        if (batch.cancellationRequested) {
            dao.requestCancellation(batchId)
            dao.finishBatch(batchId)
            return
        }
        if (!ensureDestinationAvailable(batch)) return

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

            if (!prepareNextQueuedItem(batch)) break
            val item = dao.claimNextItem(batchId) ?: break
            val inputName = checkNotNull(item.inputName) { "Queued item is missing its input name." }
            val outputName = checkNotNull(item.outputName) { "Queued item is missing its output name." }
            setForeground(createForegroundInfo(batch, inputName))
            val metrics = ProcessingMetricsRecorder.create(batchId, item.id, item.attemptCount)
            var metricsOutcome = "Failed"

            try {
                val tempFile = applicationContext.createOutputTempFile(item.id)
                val prepared = processBatchItem(
                    context = applicationContext,
                    inputUri = item.sourceUri.toUri(),
                    tempFile = tempFile,
                    quality = batch.quality,
                    inputBytes = item.inputBytes,
                    knownDimensions = item.persistedDimensions(),
                    onDimensionsRead = { dimensions ->
                        try {
                            if (dao.recordDimensions(item.id, dimensions.width, dimensions.height) != 1) {
                                throw JpegStatePersistenceException("Could not persist source dimensions.")
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: JpegStatePersistenceException) {
                            throw error
                        } catch (error: Exception) {
                            throw JpegStatePersistenceException(
                                "Could not persist source dimensions.",
                                error
                            )
                        }
                    },
                    metrics = metrics
                )
                val published = metrics.measure(ProcessingStage.Publication) {
                    applicationContext.publishPreparedOutput(
                        tempFile = tempFile,
                        outputFolderUri = batch.destinationUri.toUri(),
                        outputName = outputName,
                        itemId = item.id,
                        onOutputCreationStarting = {
                            check(dao.markOutputCreationStarted(item.id) == 1) {
                                "Could not journal output creation."
                            }
                        },
                        onOutputCleanupConfirmed = {
                            check(dao.clearIncompleteOutputUri(item.id, item.batchId)) {
                                "Could not clear the output-creation journal."
                            }
                        },
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
                }
                metrics.recordIo(
                    stage = ProcessingStage.Publication,
                    bytesRead = tempFile.length(),
                    bytesWritten = published.outputBytes
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
                repository.releaseSourcePermissionIfUnused(item.sourceUri.toUri())
                if (!applicationContext.deleteOutputTempFile(item.id)) {
                    throw RetryableCleanupException("Could not remove the prepared temporary output.")
                }
                metricsOutcome = prepared.decision.name
            } catch (cancelled: CancellationException) {
                metricsOutcome = "Interrupted"
                throw cancelled
            } catch (error: Exception) {
                metricsOutcome = if (error.isRetryableItemFailure()) "RetryableFailure" else "PermanentFailure"
                handleItemFailure(item, error)
            } finally {
                metrics.finish(metricsOutcome)
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
        if (!cleanup.temporaryOutputsCleaned || item.id in cleanup.blockedProcessingItemIds) {
            markCleanupBlocked(item.batchId)
            throw CleanupBlockedException()
        }
        val current = dao.getItem(item.id) ?: return
        if (current.state != BatchItemState.Processing) return

        val message = error.message ?: "Image processing failed."
        if (error.isRetryableItemFailure()) {
            dao.requeueOrFailProcessingItem(
                itemId = current.id,
                batchId = current.batchId,
                maxAttempts = MAX_ITEM_ATTEMPTS,
                message = "$message The image could not be processed after $MAX_ITEM_ATTEMPTS attempts."
            )
            repository.releaseSourcePermissionIfUnused(current.sourceUri.toUri())
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
        repository.releaseSourcePermissionIfUnused(current.sourceUri.toUri())
    }

    private suspend fun cleanupInterruptedBatch(batchId: String): BatchArtifactCleanupReport {
        val cleanup = repository.cleanupAbandonedArtifacts()
        dao.recoverInterruptedItems(
            batchId = batchId,
            maxAttempts = MAX_ITEM_ATTEMPTS,
            blockedItemIds = cleanup.blockedProcessingItemIds
        )
        return cleanup
    }

    private suspend fun persistInterruption(batchId: String, message: String) = withContext(NonCancellable) {
        if (dao.getBatch(batchId)?.state?.isActive != true) return@withContext

        val cleanup = cleanupInterruptedBatch(batchId)
        if (!cleanup.temporaryOutputsCleaned || cleanup.blockedProcessingItemIds.isNotEmpty()) {
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

    private class RetryableWorkerFailure(message: String) : IOException(message)

    private suspend fun markCleanupBlocked(batchId: String) {
        val message = "Waiting to remove an incomplete output before retrying."
        dao.markForWorkerRetry(batchId, message)
        dao.recordActiveError(batchId, message)
    }

    private suspend fun preserveForManualRecovery(batchId: String, message: String) {
        val cleanup = cleanupInterruptedBatch(batchId)
        if (!cleanup.temporaryOutputsCleaned || cleanup.blockedProcessingItemIds.isNotEmpty()) {
            markCleanupBlocked(batchId)
            return
        }
        dao.markForWorkerRetry(batchId, message)
        dao.finishBatch(batchId)
    }

    private suspend fun prepareNextQueuedItem(batch: BatchEntity): Boolean {
        val item = dao.getNextQueuedItemForPreparation(batch.id) ?: return false
        if (item.inputName != null && item.outputName != null) return true

        setForeground(
            createForegroundInfo(
                batch,
                "Preparing image ${item.position + 1} of ${batch.totalCount}"
            )
        )
        val sourceUri = item.sourceUri.toUri()
        val inputName = applicationContext.resolveDisplayName(sourceUri)
        val outputName = collisionResistantOutputName(
            inputName = sanitizeOutputFileName(inputName),
            batchId = batch.id,
            position = item.position
        )
        val prepared = dao.prepareItem(
                itemId = item.id,
                batchId = batch.id,
                inputName = inputName,
                outputName = outputName,
                inputBytes = applicationContext.resolveContentSize(sourceUri)
            )
        if (!prepared && dao.getBatch(batch.id)?.cancellationRequested != true) {
            error("Could not persist image metadata.")
        }
        return prepared
    }

    private fun collisionResistantOutputName(inputName: String, batchId: String, position: Int): String {
        val dot = inputName.lastIndexOf('.')
        val extension = if (dot > 0) inputName.substring(dot) else ".jpg"
        val stem = if (dot > 0) inputName.substring(0, dot) else inputName
        val batchToken = batchId.filter(Char::isLetterOrDigit).take(8).ifBlank { "batch" }
        return "${stem.take(80).ifBlank { "image" }}-slicer-$batchToken-${position + 1}$extension"
    }

    private fun BatchItemEntity.persistedDimensions() =
        if (width > 0 && height > 0) com.example.slicer.processing.JpegDimensions(width, height) else null

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
                throw RetryableWorkerFailure("Output provider is temporarily unavailable.")
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
    }
}
