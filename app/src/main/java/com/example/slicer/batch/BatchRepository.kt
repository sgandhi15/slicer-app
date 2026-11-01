package com.example.slicer.batch

import com.example.slicer.core.FULL_SIZE
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
import androidx.core.net.toUri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
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
    val blockedProcessingItemIds: Set<String>,
    val temporaryOutputsCleaned: Boolean
)

internal class BatchRepository(
    private val context: Context,
    private val dao: BatchDao,
    private val workManager: WorkManager = WorkManager.getInstance(context),
    private val sourceUriPermissions: SourceUriPermissionStore = AndroidSourceUriPermissionStore(context)
) {
    private val sourceLifecycleMutex = Mutex()

    fun observeLatestBatch(): Flow<BatchEntity?> = dao.observeLatestBatch()

    fun observeCurrentItem(batchId: String): Flow<BatchItemEntity?> = dao.observeCurrentItem(batchId)

    fun pagedResults(batchId: String): Flow<PagingData<BatchItemEntity>> = Pager(
        config = PagingConfig(
            pageSize = RESULT_PAGE_SIZE,
            initialLoadSize = RESULT_PAGE_SIZE,
            prefetchDistance = RESULT_PREFETCH_DISTANCE,
            enablePlaceholders = false,
            maxSize = RESULT_MAX_LOADED_ITEMS
        ),
        pagingSourceFactory = { dao.pagingResults(batchId) }
    ).flow

    suspend fun createAndEnqueueBatch(
        sourceUris: List<Uri>,
        destinationUri: Uri,
        quality: Int,
        cancellationRequested: () -> Boolean = { false },
        maxDimension: Int = FULL_SIZE
    ): String = withContext(Dispatchers.IO) {
        sourceLifecycleMutex.withLock {
            createAndEnqueueBatchLocked(sourceUris, destinationUri, quality, cancellationRequested, maxDimension)
        }
    }

    private suspend fun createAndEnqueueBatchLocked(
        sourceUris: List<Uri>,
        destinationUri: Uri,
        quality: Int,
        cancellationRequested: () -> Boolean,
        maxDimension: Int
    ): String {
        require(sourceUris.isNotEmpty()) { "Select at least one image." }

        val distinctSourceUris = sourceUris.distinct()
        reconcileSourcePermissionsLocked(protectedUris = distinctSourceUris.toSet())
        require(distinctSourceUris.size <= MAX_PERSISTED_SOURCES_PER_BATCH) {
            "Select at most $MAX_PERSISTED_SOURCES_PER_BATCH images per batch."
        }
        val readablePersistedUris = sourceUriPermissions.persistedReadUris().toMutableSet()
        val requiredNewGrants = distinctSourceUris.count { uri ->
            uri.requiresPersistedReadPermission() && !uri.hasReadAccessIn(readablePersistedUris)
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
            maxDimension = maxDimension,
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

            items.zip(distinctSourceUris).forEach { (item, uri) ->
                if (cancellationRequested() || dao.getBatch(batchId)?.cancellationRequested == true) {
                    dao.requestCancellation(batchId)
                    reconcileSourcePermissionsLocked()
                    return batchId
                }
                if (!hasDurableReadPermission(uri, readablePersistedUris)) {
                    dao.failUnfinishedItem(
                        itemId = item.id,
                        batchId = batchId,
                        message = "Durable source access could not be saved. Select this file again."
                    )
                }
            }
            dao.finishBatch(batchId)
            if (dao.getBatch(batchId)?.state?.isTerminal == true) {
                reconcileSourcePermissionsLocked()
                return batchId
            }

            sourceAccessReady = true
            if (cancellationRequested() || dao.getBatch(batchId)?.cancellationRequested == true) {
                dao.requestCancellation(batchId)
                reconcileSourcePermissionsLocked()
                return batchId
            }
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
        withContext(NonCancellable) {
            val changed = dao.requestCancellation(batchId)
            sourceLifecycleMutex.withLock {
                reconcileSourcePermissionsLocked()
                if (changed && dao.getProcessingItems(batchId).isNotEmpty()) {
                    try {
                        enqueueCleanup(batchId)
                    } catch (error: Exception) {
                        dao.recordActiveError(
                            batchId,
                            error.message ?: "Could not schedule incomplete-output cleanup."
                        )
                    }
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
                else -> context.cleanupUntrackedSafOutput(
                    outputFolderUri = destinationUri,
                    itemId = item.id
                )
            }
            if (!cleaned) {
                blockedItemIds += item.id
            } else if (item.outputCleanupPending) {
                dao.clearIncompleteOutputUri(item.id, item.batchId)
            }
        }

        BatchArtifactCleanupReport(
            blockedProcessingItemIds = blockedItemIds,
            temporaryOutputsCleaned = temporaryOutputsCleaned
        )
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
        if (uri.hasReadAccessIn(readablePersistedUris)) return true
        // Android can't grant files inside a picked folder one by one; only the folder's grant works.
        if (DocumentsContract.isTreeUri(uri)) return false

        val granted = sourceUriPermissions.persistReadPermission(uri)
        if (granted) readablePersistedUris.add(uri)
        return granted
    }

    private suspend fun failBatchesWithMissingSourcePermissionsLocked(
        blockedProcessingItemIds: Set<String> = emptySet()
    ) {
        val persistedUris = sourceUriPermissions.persistedReadUris()
        val missingItems = dao.getItemsRequiringSourcePermission()
            .filter { item ->
                val sourceUri = item.sourceUri.toUri()
                sourceUri.requiresPersistedReadPermission() && !sourceUri.hasReadAccessIn(persistedUris)
            }
        missingItems.forEach { item ->
            if (item.id in blockedProcessingItemIds) return@forEach
            dao.failUnfinishedItem(
                itemId = item.id,
                batchId = item.batchId,
                message = "Durable source access is no longer available. Select this file again."
            )
        }
        missingItems.map(BatchItemEntity::batchId).distinct().forEach { batchId ->
            dao.finishBatch(batchId)
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
        const val RESULT_PAGE_SIZE = 50
        const val RESULT_PREFETCH_DISTANCE = 10
        const val RESULT_MAX_LOADED_ITEMS = 150
    }

}

private fun Uri.hasReadAccessIn(persistedUris: Set<Uri>): Boolean {
    if (this in persistedUris) return true
    if (!DocumentsContract.isTreeUri(this)) return false
    val folder = DocumentsContract.buildTreeDocumentUri(authority, DocumentsContract.getTreeDocumentId(this))
    return folder in persistedUris
}

private fun Uri.requiresPersistedReadPermission(): Boolean {
    return scheme == ContentResolver.SCHEME_CONTENT
}
