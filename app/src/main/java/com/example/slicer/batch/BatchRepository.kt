package com.example.slicer.batch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkRequest
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.example.slicer.storage.cleanupAbandonedOutputTemps
import com.example.slicer.storage.cleanupOrphanedPendingMediaStoreOutputs
import com.example.slicer.storage.cleanupUntrackedSafOutput
import com.example.slicer.storage.deleteOutputDocument
import com.example.slicer.storage.isDefaultOutputFolder
import com.example.slicer.storage.OutputFolderAccess
import com.example.slicer.storage.outputFolderAccess
import android.util.Log
import com.example.slicer.core.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class BatchArtifactCleanupReport(
    val blockedProcessingItemIds: Set<String>
)

internal class BatchRepository(
    private val context: Context,
    private val dao: BatchDao,
    private val workManager: WorkManager = WorkManager.getInstance(context)
) {
    fun observeLatestBatch(): Flow<BatchEntity?> = dao.observeLatestBatch()

    fun observeItems(batchId: String): Flow<List<BatchItemEntity>> = dao.observeItems(batchId)

    suspend fun createAndEnqueueBatch(
        sourceUris: List<Uri>,
        destinationUri: Uri,
        quality: Int,
        cancellationRequested: () -> Boolean = { false }
    ): String = withContext(Dispatchers.IO) {
        require(sourceUris.isNotEmpty()) { "Select at least one image." }

        val batchId = UUID.randomUUID().toString()
        val distinctSourceUris = sourceUris.distinct()
        val items = distinctSourceUris.mapIndexed { index, uri ->
            BatchItemEntity(
                id = UUID.randomUUID().toString(),
                batchId = batchId,
                position = index,
                sourceUri = uri.toString()
            )
        }
        val batch = BatchEntity(
            id = batchId,
            createdAt = System.currentTimeMillis(),
            quality = quality,
            destinationUri = destinationUri.toString(),
            totalCount = items.size
        )
        try {
            dao.createBatch(batch, items)
            if (cancellationRequested()) {
                dao.requestCancellation(batchId)
                return@withContext batchId
            }

            val readablePersistedUris = context.contentResolver.persistedUriPermissions
                .filter { permission -> permission.isReadPermission }
                .map { permission -> permission.uri }
                .toMutableSet()
            val missingPermissions = distinctSourceUris.filterNot { uri ->
                hasDurableReadPermission(uri, readablePersistedUris)
            }
            if (missingPermissions.isNotEmpty()) {
                dao.failBatch(
                    batchId,
                    "Durable access could not be saved for ${missingPermissions.size} selected file(s). Select them again."
                )
                return@withContext batchId
            }

            enqueue(batchId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (dao.getBatch(batchId) != null) {
                    if (cancellationRequested()) {
                        dao.requestCancellation(batchId)
                    } else {
                        dao.markForWorkerRetry(batchId, "Scheduling batch processing was interrupted.")
                    }
                }
            }
            throw cancelled
        } catch (error: Exception) {
            if (dao.getBatch(batchId) != null) {
                dao.markForWorkerRetry(batchId, error.message ?: "Could not schedule batch processing.")
                return@withContext batchId
            }
            throw error
        }
        batchId
    }

    suspend fun resumeIncompleteBatches() {
        dao.getActiveBatchIds().forEach { batchId ->
            try {
                enqueue(batchId)
            } catch (error: Exception) {
                dao.markForWorkerRetry(batchId, error.message ?: "Could not resume batch processing.")
            }
        }
    }

    suspend fun requestCancellation(batchId: String) {
        if (dao.requestCancellation(batchId) && dao.getProcessingItems(batchId).isNotEmpty()) {
            try {
                enqueueCleanup(batchId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                dao.recordActiveError(
                    batchId,
                    error.message ?: "Could not schedule incomplete-output cleanup."
                )
            }
        }
    }

    suspend fun cleanupAbandonedArtifacts() = withContext(Dispatchers.IO) {
        val processingItems = dao.getAllProcessingItems()
        val blockedItemIds = mutableSetOf<String>()
        val temporaryOutputsCleaned = context.cleanupAbandonedOutputTemps()
        if (!temporaryOutputsCleaned) {
            Log.w(LOG_TAG, "Could not remove every abandoned temporary output.")
            processingItems.mapTo(blockedItemIds) { item -> item.id }
        }

        val pendingMediaStoreOutputsCleaned = context.cleanupOrphanedPendingMediaStoreOutputs()
        if (!pendingMediaStoreOutputsCleaned) {
            Log.w(LOG_TAG, "Could not remove every abandoned pending MediaStore output.")
        }

        dao.getIncompleteOutputItems().forEach { item ->
            val outputUri = item.outputUri?.let(Uri::parse) ?: return@forEach
            val batch = dao.getBatch(item.batchId)
            val destinationUri = batch?.destinationUri?.let(Uri::parse)
            val uriCleaned = context.deleteOutputDocument(outputUri)
            val namesCleaned = destinationUri == null || destinationUri.isDefaultOutputFolder() ||
                context.cleanupUntrackedSafOutput(
                    outputFolderUri = destinationUri,
                    itemId = item.id
                )
            if (uriCleaned && namesCleaned) {
                dao.clearIncompleteOutputUri(item.id, item.batchId)
            } else if (item.state == BatchItemState.Processing) {
                blockedItemIds += item.id
            }
        }

        processingItems.filter { item -> item.outputUri == null }.forEach { item ->
            val batch = dao.getBatch(item.batchId)
            val destinationUri = batch?.destinationUri?.let(Uri::parse)
            val cleaned = when {
                destinationUri == null -> false
                destinationUri.isDefaultOutputFolder() -> pendingMediaStoreOutputsCleaned
                else -> context.cleanupUntrackedSafOutput(destinationUri, item.id)
            }
            if (!cleaned) blockedItemIds += item.id
        }

        BatchArtifactCleanupReport(blockedItemIds)
    }

    suspend fun scheduleItemRetry(batchId: String, attemptCount: Int) {
        val multiplier = 1L shl (attemptCount - 1).coerceIn(0, MAX_RETRY_DELAY_EXPONENT)
        enqueue(
            batchId = batchId,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            initialDelayMillis = WorkRequest.MIN_BACKOFF_MILLIS * multiplier
        )
    }

    suspend fun cancelActiveBatches() {
        withContext(Dispatchers.IO) {
            dao.getActiveBatchIds().forEach { batchId -> requestCancellation(batchId) }
        }
    }

    suspend fun replaceDestinationAndResume(batchId: String, destinationUri: Uri) {
        withContext(Dispatchers.IO) {
            when (context.outputFolderAccess(destinationUri)) {
                OutputFolderAccess.Writable -> Unit
                OutputFolderAccess.PermissionMissing -> error("Output folder permission is no longer available.")
                OutputFolderAccess.TemporarilyUnavailable -> error("Output folder provider is temporarily unavailable.")
            }
            check(dao.replaceDestination(batchId, destinationUri.toString()) == 1) {
                "This batch is not waiting for a new output folder."
            }
            try {
                enqueue(batchId, ExistingWorkPolicy.APPEND_OR_REPLACE)
            } catch (error: Exception) {
                dao.markForWorkerRetry(batchId, error.message ?: "Could not resume batch processing.")
            }
        }
    }

    private fun hasDurableReadPermission(uri: Uri, readablePersistedUris: MutableSet<Uri>): Boolean {
        if (uri in readablePersistedUris) return true

        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val granted = context.contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission
            }
            if (granted) readablePersistedUris.add(uri)
            granted
        }.getOrDefault(false)
    }

    private suspend fun enqueue(
        batchId: String,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        initialDelayMillis: Long = 0L
    ) {
        val requestBuilder = OneTimeWorkRequestBuilder<BatchWorker>()
            .setInputData(workDataOf(BatchWorker.BATCH_ID_KEY to batchId))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
        if (initialDelayMillis > 0L) {
            requestBuilder.setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
        }
        val request = requestBuilder.build()
        workManager.enqueueUniqueWork(batchWorkName(batchId), policy, request).await()
    }

    private suspend fun enqueueCleanup(batchId: String) {
        val request = OneTimeWorkRequestBuilder<BatchWorker>()
            .setInputData(workDataOf(BatchWorker.BATCH_ID_KEY to batchId))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()
        workManager.enqueueUniqueWork(
            batchCleanupWorkName(batchId),
            ExistingWorkPolicy.KEEP,
            request
        ).await()
    }

    private companion object {
        const val MAX_RETRY_DELAY_EXPONENT = 4
    }

}
