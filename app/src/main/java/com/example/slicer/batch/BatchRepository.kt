package com.example.slicer.batch

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkRequest
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.example.slicer.core.LOG_TAG
import com.example.slicer.storage.AndroidSourceUriPermissionStore
import com.example.slicer.storage.OutputFolderAccess
import com.example.slicer.storage.SourceUriPermissionStore
import com.example.slicer.storage.cleanupAbandonedOutputTemps
import com.example.slicer.storage.cleanupOrphanedPendingMediaStoreOutputs
import com.example.slicer.storage.cleanupUntrackedSafOutput
import com.example.slicer.storage.deleteOutputDocument
import com.example.slicer.storage.isDefaultOutputFolder
import com.example.slicer.storage.outputFolderAccess
import com.example.slicer.storage.withOutputLifecycleLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class BatchArtifactCleanupReport(
    val blockedProcessingItemIds: Set<String>
)

internal class BatchRepository(
    private val context: Context,
    private val dao: BatchDao,
    private val workManager: WorkManager = WorkManager.getInstance(context),
    private val sourceUriPermissions: SourceUriPermissionStore = AndroidSourceUriPermissionStore(context)
) {
    private val sourceLifecycleMutex = Mutex()

    fun observeLatestBatch(): Flow<BatchEntity?> = dao.observeLatestBatch()

    fun observeItems(batchId: String): Flow<List<BatchItemEntity>> = dao.observeItems(batchId)

    suspend fun createAndEnqueueBatch(
        sourceUris: List<Uri>,
        destinationUri: Uri,
        quality: Int,
        cancellationRequested: () -> Boolean = { false }
    ): String = withContext(Dispatchers.IO) {
        sourceLifecycleMutex.withLock {
            createAndEnqueueBatchLocked(sourceUris, destinationUri, quality, cancellationRequested)
        }
    }

    private suspend fun createAndEnqueueBatchLocked(
        sourceUris: List<Uri>,
        destinationUri: Uri,
        quality: Int,
        cancellationRequested: () -> Boolean
    ): String {
        require(sourceUris.isNotEmpty()) { "Select at least one image." }

        val distinctSourceUris = sourceUris.distinct()
        reconcileSourcePermissionsLocked(protectedUris = distinctSourceUris.toSet())
        require(distinctSourceUris.size <= MAX_PERSISTED_SOURCES_PER_BATCH) {
            "Select at most $MAX_PERSISTED_SOURCES_PER_BATCH images per batch."
        }
        val readablePersistedUris = sourceUriPermissions.persistedReadUris().toMutableSet()
        val requiredNewGrants = distinctSourceUris.count { uri ->
            uri.requiresPersistedReadPermission() && uri !in readablePersistedUris
        }
        check(sourceUriPermissions.persistedPermissionCount() + requiredNewGrants <= PERSISTED_URI_GRANT_BUDGET) {
            "Not enough durable file-access slots are available. Finish existing work or select fewer images."
        }

        val batchId = UUID.randomUUID().toString()
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
        var sourceAccessReady = false
        try {
            dao.createBatch(batch, items)
            if (cancellationRequested()) {
                dao.requestCancellation(batchId)
                reconcileSourcePermissionsLocked()
                return batchId
            }

            val missingPermissions = distinctSourceUris.filterNot { uri ->
                hasDurableReadPermission(uri, readablePersistedUris)
            }
            if (missingPermissions.isNotEmpty()) {
                dao.failBatch(
                    batchId,
                    "Durable access could not be saved for ${missingPermissions.size} selected file(s). Select them again."
                )
                reconcileSourcePermissionsLocked()
                return batchId
            }

            sourceAccessReady = true
            enqueue(batchId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (dao.getBatch(batchId) != null) {
                    when {
                        cancellationRequested() -> dao.requestCancellation(batchId)
                        !sourceAccessReady -> dao.failBatch(
                            batchId,
                            "Batch setup was interrupted before durable source access was ready."
                        )
                        else -> dao.markForWorkerRetry(
                            batchId,
                            "Scheduling batch processing was interrupted."
                        )
                    }
                    reconcileSourcePermissionsLocked()
                }
            }
            throw cancelled
        } catch (error: Exception) {
            if (dao.getBatch(batchId) != null) {
                if (sourceAccessReady) {
                    dao.markForWorkerRetry(batchId, error.message ?: "Could not schedule batch processing.")
                } else {
                    dao.failBatch(batchId, error.message ?: "Could not secure durable source access.")
                    reconcileSourcePermissionsLocked()
                }
                return batchId
            }
            throw error
        }
        return batchId
    }

    suspend fun resumeIncompleteBatches() = withOutputLifecycleLock {
        val cleanup = cleanupAbandonedArtifacts()
        withContext(Dispatchers.IO) {
            sourceLifecycleMutex.withLock {
                reconcileSourcePermissionsLocked()
                failBatchesWithMissingSourcePermissionsLocked(cleanup.blockedProcessingItemIds)
                reconcileSourcePermissionsLocked()
                dao.getActiveBatchIds().forEach { batchId ->
                    try {
                        enqueue(batchId)
                    } catch (error: Exception) {
                        dao.markForWorkerRetry(batchId, error.message ?: "Could not resume batch processing.")
                    }
                }
            }
        }
    }

    suspend fun requestCancellation(batchId: String) = withContext(Dispatchers.IO) {
        sourceLifecycleMutex.withLock {
            val changed = dao.requestCancellation(batchId)
            reconcileSourcePermissionsLocked()
            if (changed && dao.getProcessingItems(batchId).isNotEmpty()) {
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
    }

    suspend fun reconcileSourcePermissions() = withContext(Dispatchers.IO) {
        sourceLifecycleMutex.withLock { reconcileSourcePermissionsLocked() }
    }

    suspend fun releaseSourcePermissionIfUnused(sourceUri: Uri) = withContext(Dispatchers.IO) {
        sourceLifecycleMutex.withLock { releaseSourcePermissionIfUnusedLocked(sourceUri) }
    }

    suspend fun reconcileRequiredSourcePermissions() = withContext(Dispatchers.IO) {
        sourceLifecycleMutex.withLock {
            failBatchesWithMissingSourcePermissionsLocked()
            reconcileSourcePermissionsLocked()
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
        enqueue(
            batchId = batchId,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            initialDelayMillis = itemRetryDelayMillis(
                attemptCount = attemptCount,
                baseDelayMillis = WorkRequest.MIN_BACKOFF_MILLIS
            )
        )
    }

    suspend fun cancelActiveBatches() {
        withContext(Dispatchers.IO) {
            dao.getActiveBatchIds().forEach { batchId -> requestCancellation(batchId) }
        }
    }

    suspend fun replaceDestinationAndResume(batchId: String, destinationUri: Uri) = withOutputLifecycleLock {
        withContext(Dispatchers.IO) {
            sourceLifecycleMutex.withLock {
                failBatchesWithMissingSourcePermissionsLocked()
                reconcileSourcePermissionsLocked()
                when (context.outputFolderAccess(destinationUri)) {
                    OutputFolderAccess.Writable -> Unit
                    OutputFolderAccess.PermissionMissing -> error("Output folder permission is no longer available.")
                    OutputFolderAccess.TemporarilyUnavailable -> error(
                        "Output folder provider is temporarily unavailable."
                    )
                }
                check(dao.replaceDestination(batchId, destinationUri.toString()) == 1) {
                    dao.getBatch(batchId)?.lastError
                        ?: "This batch is not waiting for a new output folder."
                }
                try {
                    enqueue(batchId, ExistingWorkPolicy.APPEND_OR_REPLACE)
                } catch (error: Exception) {
                    dao.markForWorkerRetry(batchId, error.message ?: "Could not resume batch processing.")
                }
            }
        }
    }

    private fun hasDurableReadPermission(uri: Uri, readablePersistedUris: MutableSet<Uri>): Boolean {
        if (!uri.requiresPersistedReadPermission()) return true
        if (uri in readablePersistedUris) return true

        val granted = sourceUriPermissions.persistReadPermission(uri)
        if (granted) readablePersistedUris.add(uri)
        return granted
    }

    private suspend fun failBatchesWithMissingSourcePermissionsLocked(
        blockedProcessingItemIds: Set<String> = emptySet()
    ) {
        val persistedUris = sourceUriPermissions.persistedReadUris()
        val blockedBatchIds = blockedProcessingItemIds.mapNotNullTo(mutableSetOf()) { itemId ->
            dao.getItem(itemId)?.batchId
        }
        val missingByBatch = dao.getItemsRequiringSourcePermission()
            .filter { item ->
                val sourceUri = Uri.parse(item.sourceUri)
                sourceUri.requiresPersistedReadPermission() && sourceUri !in persistedUris
            }
            .groupBy(BatchItemEntity::batchId)
        missingByBatch.forEach { (batchId, items) ->
            if (batchId in blockedBatchIds) return@forEach
            dao.failBatch(
                batchId,
                "Durable source access is no longer available for ${items.size} file(s). Select them again."
            )
        }
    }

    private suspend fun reconcileSourcePermissionsLocked(protectedUris: Set<Uri> = emptySet()) {
        val requiredUris = dao.getRequiredSourceUris().mapTo(mutableSetOf(), Uri::parse)
        requiredUris += protectedUris
        sourceUriPermissions.persistedReadUris()
            .asSequence()
            .filter { uri -> !DocumentsContract.isTreeUri(uri) && uri !in requiredUris }
            .forEach(::releaseSourcePermissionLocked)
    }

    private suspend fun releaseSourcePermissionIfUnusedLocked(sourceUri: Uri) {
        if (DocumentsContract.isTreeUri(sourceUri)) return
        if (dao.hasNonTerminalSourceReference(sourceUri.toString())) return
        if (sourceUri !in sourceUriPermissions.persistedReadUris()) return
        releaseSourcePermissionLocked(sourceUri)
    }

    private fun releaseSourcePermissionLocked(sourceUri: Uri) {
        if (!sourceUriPermissions.releaseReadPermission(sourceUri)) {
            Log.w(LOG_TAG, "Could not release persisted source permission for '$sourceUri'.")
        }
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
        const val MAX_PERSISTED_SOURCES_PER_BATCH = 400
        const val PERSISTED_URI_GRANT_BUDGET = 480
    }

}

private fun Uri.requiresPersistedReadPermission(): Boolean {
    return scheme == ContentResolver.SCHEME_CONTENT
}
