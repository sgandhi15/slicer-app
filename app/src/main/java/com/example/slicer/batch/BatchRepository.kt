package com.example.slicer.batch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.example.slicer.storage.canWriteToOutputFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

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
                        dao.prepareForWorkerRetry(
                            batchId = batchId,
                            maxAttempts = MAX_ITEM_ATTEMPTS,
                            error = "Scheduling batch processing was interrupted."
                        )
                    }
                }
            }
            throw cancelled
        } catch (error: Exception) {
            if (dao.getBatch(batchId) != null) {
                dao.prepareForWorkerRetry(
                    batchId = batchId,
                    maxAttempts = MAX_ITEM_ATTEMPTS,
                    error = error.message ?: "Could not schedule batch processing."
                )
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
                dao.prepareForWorkerRetry(
                    batchId = batchId,
                    maxAttempts = MAX_ITEM_ATTEMPTS,
                    error = error.message ?: "Could not resume batch processing."
                )
            }
        }
    }

    suspend fun requestCancellation(batchId: String) {
        dao.requestCancellation(batchId)
    }

    suspend fun cancelActiveBatches() {
        withContext(Dispatchers.IO) {
            dao.getActiveBatchIds().forEach { batchId -> dao.requestCancellation(batchId) }
        }
    }

    suspend fun replaceDestinationAndResume(batchId: String, destinationUri: Uri) {
        withContext(Dispatchers.IO) {
            check(context.canWriteToOutputFolder(destinationUri)) {
                "Output folder permission is no longer available."
            }
            check(dao.replaceDestination(batchId, destinationUri.toString()) == 1) {
                "This batch is not waiting for a new output folder."
            }
            try {
                enqueue(batchId, ExistingWorkPolicy.APPEND_OR_REPLACE)
            } catch (error: Exception) {
                dao.prepareForWorkerRetry(
                    batchId = batchId,
                    maxAttempts = MAX_ITEM_ATTEMPTS,
                    error = error.message ?: "Could not resume batch processing."
                )
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
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        val request = OneTimeWorkRequestBuilder<BatchWorker>()
            .setInputData(workDataOf(BatchWorker.BATCH_ID_KEY to batchId))
            .build()
        workManager.enqueueUniqueWork(batchWorkName(batchId), policy, request).await()
    }

    private companion object {
        const val MAX_ITEM_ATTEMPTS = 3
    }
}
