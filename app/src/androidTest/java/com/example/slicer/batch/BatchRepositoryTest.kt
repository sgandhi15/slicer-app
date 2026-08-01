package com.example.slicer.batch

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.await as awaitOperation
import com.example.slicer.storage.SourceUriPermissionStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BatchRepositoryTest {
    private lateinit var database: BatchDatabase
    private lateinit var dao: BatchDao
    private lateinit var repository: BatchRepository
    private lateinit var workManager: WorkManager
    private lateinit var sourcePermissions: FakeSourceUriPermissionStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BatchDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.batchDao()
        workManager = WorkManager.getInstance(context)
        sourcePermissions = FakeSourceUriPermissionStore()
        repository = BatchRepository(context, dao, workManager, sourcePermissions)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun cancellationDuringStartLeavesDurableTerminalBatch() = runBlocking {
        val source = Uri.parse("content://com.example.slicer.test/source/1")

        val batchId = repository.createAndEnqueueBatch(
            sourceUris = listOf(source),
            destinationUri = Uri.parse("content://com.example.slicer.test/destination"),
            quality = 80,
            cancellationRequested = { true }
        )

        val batch = requireNotNull(dao.getBatch(batchId))
        val item = dao.getItems(batchId).single()
        assertEquals(BatchState.Cancelled, batch.state)
        assertEquals(BatchItemState.Cancelled, item.state)
        assertEquals(source.toString(), item.sourceUri)
        assertNull(item.inputName)
        assertNull(item.outputName)
    }

    @Test
    fun missingPersistedGrantFailsThePersistedBatch() = runBlocking {
        val source = Uri.parse("content://com.example.slicer.test/no-grant")
        sourcePermissions.deniedUris += source
        val batchId = repository.createAndEnqueueBatch(
            sourceUris = listOf(source),
            destinationUri = Uri.parse("content://com.example.slicer.test/destination"),
            quality = 80
        )

        val batch = requireNotNull(dao.getBatch(batchId))
        assertEquals(BatchState.Failed, batch.state)
        assertEquals(1, batch.failedCount)
        assertTrue(batch.lastError?.contains("Durable access") == true)
        assertEquals(BatchItemState.Failed, dao.getItems(batchId).single().state)
    }

    @Test
    fun resumeEnqueuesWorkForThePersistedBatchId() = runBlocking {
        val batchId = UUID.randomUUID().toString()
        val source = Uri.parse("content://source")
        sourcePermissions.grantedUris += source
        dao.createBatch(
            BatchEntity(
                id = batchId,
                createdAt = 1L,
                quality = 80,
                destinationUri = "content://destination",
                totalCount = 1
            ),
            listOf(
                BatchItemEntity(
                    id = "$batchId-item",
                    batchId = batchId,
                    position = 0,
                    sourceUri = source.toString()
                )
            )
        )

        repository.resumeIncompleteBatches()

        val workName = batchWorkName(batchId)
        val work = workManager.getWorkInfosForUniqueWork(workName).get(5, TimeUnit.SECONDS)
        assertEquals(1, work.size)
        workManager.cancelUniqueWork(workName).awaitOperation()
        Unit
    }

    @Test
    fun cancellationWithProcessingItemEnqueuesIndependentCleanupWork() = runBlocking {
        val batchId = UUID.randomUUID().toString()
        dao.createBatch(
            BatchEntity(
                id = batchId,
                createdAt = 1L,
                quality = 80,
                destinationUri = "content://destination",
                totalCount = 1
            ),
            listOf(
                BatchItemEntity(
                    id = "$batchId-item",
                    batchId = batchId,
                    position = 0,
                    sourceUri = "content://source",
                    inputName = "source.jpg",
                    outputName = "source.jpg",
                    inputBytes = 100L
                )
            )
        )
        requireNotNull(dao.claimNextItem(batchId))

        repository.requestCancellation(batchId)

        val cleanupWorkName = batchCleanupWorkName(batchId)
        val work = workManager.getWorkInfosForUniqueWork(cleanupWorkName).get(5, TimeUnit.SECONDS)
        assertEquals(1, work.size)
        workManager.cancelUniqueWork(cleanupWorkName).awaitOperation()
        Unit
    }

    @Test
    fun partialGrantFailureReleasesAlreadyAcquiredSources() = runBlocking {
        val first = Uri.parse("content://com.example.slicer.test/source/first")
        val second = Uri.parse("content://com.example.slicer.test/source/second")
        sourcePermissions.deniedUris += second

        val batchId = repository.createAndEnqueueBatch(
            sourceUris = listOf(first, second),
            destinationUri = Uri.parse("content://com.example.slicer.test/destination"),
            quality = 80
        )

        assertEquals(BatchState.Failed, dao.getBatch(batchId)?.state)
        assertFalse(first in sourcePermissions.grantedUris)
        assertTrue(first in sourcePermissions.releasedUris)
    }

    @Test
    fun terminalItemReleasesItsPersistedSource() = runBlocking {
        val batchId = UUID.randomUUID().toString()
        val source = Uri.parse("content://com.example.slicer.test/source/terminal")
        sourcePermissions.grantedUris += source
        dao.createBatch(
            BatchEntity(
                id = batchId,
                createdAt = 1L,
                quality = 80,
                destinationUri = "content://destination",
                totalCount = 1
            ),
            listOf(
                BatchItemEntity(
                    id = "$batchId-item",
                    batchId = batchId,
                    position = 0,
                    sourceUri = source.toString(),
                    inputName = "source.jpg",
                    outputName = "source.jpg",
                    inputBytes = 100L
                )
            )
        )
        val item = requireNotNull(dao.claimNextItem(batchId))
        dao.completeItem(
            itemId = item.id,
            batchId = batchId,
            state = BatchItemState.Compressed,
            outputUri = "content://output/source.jpg",
            outputBytes = 50L,
            message = null
        )

        repository.reconcileSourcePermissions()

        assertFalse(source in sourcePermissions.grantedUris)
        assertTrue(source in sourcePermissions.releasedUris)
    }

    @Test
    fun startupFailsAnUnfinishedBatchWhoseGrantWasLost() = runBlocking {
        val batchId = UUID.randomUUID().toString()
        dao.createBatch(
            BatchEntity(
                id = batchId,
                createdAt = 1L,
                quality = 80,
                destinationUri = "content://destination",
                state = BatchState.NeedsDestination,
                totalCount = 1
            ),
            listOf(
                BatchItemEntity(
                    id = "$batchId-item",
                    batchId = batchId,
                    position = 0,
                    sourceUri = "content://source/missing"
                )
            )
        )

        repository.resumeIncompleteBatches()

        val batch = requireNotNull(dao.getBatch(batchId))
        assertEquals(BatchState.Failed, batch.state)
        assertEquals(BatchItemState.Failed, dao.getItems(batchId).single().state)
    }

    @Test
    fun reconciliationReleasesLegacyDocumentGrantWithoutADatabaseRow() = runBlocking {
        val orphanedSource = Uri.parse("content://com.example.slicer.test/document/orphaned")
        sourcePermissions.grantedUris += orphanedSource

        repository.reconcileSourcePermissions()

        assertFalse(orphanedSource in sourcePermissions.grantedUris)
        assertTrue(orphanedSource in sourcePermissions.releasedUris)
    }

    @Test
    fun reconciliationDoesNotReleaseDestinationTreeGrant() = runBlocking {
        val destinationTree = Uri.parse("content://com.example.slicer.test/tree/output")
        sourcePermissions.grantedUris += destinationTree

        repository.reconcileSourcePermissions()

        assertTrue(destinationTree in sourcePermissions.grantedUris)
        assertFalse(destinationTree in sourcePermissions.releasedUris)
    }

    @Test
    fun preflightCleanupRetainsAnIncomingPersistedSourceGrant() = runBlocking {
        val source = Uri.parse("content://com.example.slicer.test/document/restored-selection")
        sourcePermissions.grantedUris += source
        sourcePermissions.deniedUris += source

        val batchId = repository.createAndEnqueueBatch(
            sourceUris = listOf(source),
            destinationUri = Uri.parse("content://com.example.slicer.test/tree/destination"),
            quality = 80
        )

        assertEquals(BatchState.Queued, dao.getBatch(batchId)?.state)
        assertTrue(source in sourcePermissions.grantedUris)
        assertFalse(source in sourcePermissions.releasedUris)
        workManager.cancelUniqueWork(batchWorkName(batchId)).awaitOperation()
        Unit
    }

    @Test
    fun missingSourceRecoveryPreservesJournalWhenOutputCleanupIsBlocked() = runBlocking {
        val batchId = UUID.randomUUID().toString()
        dao.createBatch(
            BatchEntity(
                id = batchId,
                createdAt = 1L,
                quality = 80,
                destinationUri = "content://com.example.slicer.test/tree/unavailable",
                totalCount = 1
            ),
            listOf(
                BatchItemEntity(
                    id = "$batchId-item",
                    batchId = batchId,
                    position = 0,
                    sourceUri = "content://com.example.slicer.test/document/missing-source",
                    inputName = "source.jpg",
                    outputName = "source.jpg",
                    inputBytes = 100L
                )
            )
        )
        val item = requireNotNull(dao.claimNextItem(batchId))
        val provisionalOutput = "content://com.example.slicer.test/document/unavailable-output"
        dao.recordOutputUri(item.id, provisionalOutput, "unavailable-output.jpg")

        repository.resumeIncompleteBatches()

        val retained = requireNotNull(dao.getItem(item.id))
        assertEquals(BatchItemState.Processing, retained.state)
        assertEquals(provisionalOutput, retained.outputUri)
        assertTrue(dao.getBatch(batchId)?.state != BatchState.Failed)
        workManager.cancelUniqueWork(batchWorkName(batchId)).awaitOperation()
        Unit
    }

    private class FakeSourceUriPermissionStore : SourceUriPermissionStore {
        val grantedUris = linkedSetOf<Uri>()
        val deniedUris = mutableSetOf<Uri>()
        val releasedUris = mutableSetOf<Uri>()

        override fun persistedReadUris(): Set<Uri> = grantedUris.toSet()

        override fun persistedPermissionCount(): Int = grantedUris.size

        override fun persistReadPermission(uri: Uri): Boolean {
            if (uri in deniedUris) return false
            grantedUris += uri
            return true
        }

        override fun releaseReadPermission(uri: Uri): Boolean {
            grantedUris -= uri
            releasedUris += uri
            return true
        }
    }
}
