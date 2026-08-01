package com.example.slicer.batch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchDaoTest {
    private lateinit var database: BatchDatabase
    private lateinit var dao: BatchDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BatchDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.batchDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun terminalItemIsCountedOnlyOnce() = runBlocking {
        createBatch(itemCount = 1)
        val item = requireNotNull(dao.claimNextItem(BATCH_ID))

        assertTrue(
            dao.completeItem(
                itemId = item.id,
                batchId = BATCH_ID,
                state = BatchItemState.Compressed,
                outputUri = "content://output/1",
                outputBytes = 60L,
                message = null
            )
        )
        assertFalse(
            dao.completeItem(
                itemId = item.id,
                batchId = BATCH_ID,
                state = BatchItemState.Compressed,
                outputUri = "content://output/1",
                outputBytes = 60L,
                message = null
            )
        )

        val batch = requireNotNull(dao.getBatch(BATCH_ID))
        assertEquals(1, batch.completedCount)
        assertEquals(1, batch.compressedCount)
        assertEquals(60L, batch.totalOutputBytes)
    }

    @Test
    fun itemCannotBeClaimedUntilDurableMetadataIsPrepared() = runBlocking {
        createBatch(itemCount = 1, prepared = false)

        assertEquals(null, dao.claimNextItem(BATCH_ID))
        assertTrue(
            dao.prepareItem(
                itemId = "$BATCH_ID-item-0",
                batchId = BATCH_ID,
                inputName = "prepared.jpg",
                outputName = "prepared.jpg",
                inputBytes = 250L
            )
        )

        val item = requireNotNull(dao.claimNextItem(BATCH_ID))
        assertEquals("prepared.jpg", item.inputName)
        assertEquals("prepared.jpg", item.outputName)
        assertEquals(250L, item.inputBytes)
        assertEquals(250L, dao.getBatch(BATCH_ID)?.totalInputBytes)
    }

    @Test
    fun interruptedItemIsRequeued() = runBlocking {
        createBatch(itemCount = 2)
        val interrupted = requireNotNull(dao.claimNextItem(BATCH_ID))

        assertEquals(1, dao.recoverInterruptedItems(BATCH_ID, maxAttempts = 3))
        val retried = requireNotNull(dao.claimNextItem(BATCH_ID))

        assertEquals(0, interrupted.position)
        assertEquals(interrupted.id, retried.id)
        assertEquals(2, retried.attemptCount)
        assertEquals(0, dao.getBatch(BATCH_ID)?.failedCount)
    }

    @Test
    fun interruptedItemIsNotRequeuedUntilArtifactCleanupSucceeds() = runBlocking {
        createBatch(itemCount = 1)
        val interrupted = requireNotNull(dao.claimNextItem(BATCH_ID))

        assertEquals(
            0,
            dao.recoverInterruptedItems(
                batchId = BATCH_ID,
                maxAttempts = 3,
                blockedItemIds = setOf(interrupted.id)
            )
        )

        val retained = dao.getItems(BATCH_ID).single()
        assertEquals(BatchItemState.Processing, retained.state)
        assertEquals(1, retained.attemptCount)
        assertEquals(null, dao.claimNextItem(BATCH_ID))
    }

    @Test
    fun interruptedItemClearsCleanedOutputUriWhenRequeued() = runBlocking {
        createBatch(itemCount = 1)
        val interrupted = requireNotNull(dao.claimNextItem(BATCH_ID))
        dao.recordOutputUri(
            interrupted.id,
            "content://output/interrupted",
            actualOutputName = "provider-name.jpg"
        )

        assertEquals(1, dao.recoverInterruptedItems(BATCH_ID, maxAttempts = 3))

        val requeued = dao.getItems(BATCH_ID).single()
        assertEquals(BatchItemState.Queued, requeued.state)
        assertEquals(null, requeued.outputUri)
        assertEquals(null, requeued.actualOutputName)
    }

    @Test
    fun secondItemCannotBeClaimedWhileOneIsProcessing() = runBlocking {
        createBatch(itemCount = 2)
        val first = requireNotNull(dao.claimNextItem(BATCH_ID))

        assertEquals(null, dao.claimNextItem(BATCH_ID))

        dao.completeItem(
            itemId = first.id,
            batchId = BATCH_ID,
            state = BatchItemState.CopiedOriginal,
            outputUri = "content://output/first",
            outputBytes = 100L,
            message = null
        )
        assertEquals(1, requireNotNull(dao.claimNextItem(BATCH_ID)).position)
    }

    @Test
    fun transientFailureRequeuesCurrentItemUntilAttemptLimit() = runBlocking {
        createBatch(itemCount = 1)
        val firstAttempt = requireNotNull(dao.claimNextItem(BATCH_ID))

        assertTrue(
            dao.requeueOrFailProcessingItem(
                itemId = firstAttempt.id,
                batchId = BATCH_ID,
                maxAttempts = 2,
                message = "Provider unavailable."
            )
        )

        val secondAttempt = requireNotNull(dao.claimNextItem(BATCH_ID))
        assertEquals(2, secondAttempt.attemptCount)

        assertTrue(
            dao.requeueOrFailProcessingItem(
                itemId = secondAttempt.id,
                batchId = BATCH_ID,
                maxAttempts = 2,
                message = "Provider unavailable."
            )
        )

        val failed = dao.getItems(BATCH_ID).single()
        assertEquals(BatchItemState.Failed, failed.state)
        assertEquals("Provider unavailable.", failed.message)
        assertEquals(1, dao.getBatch(BATCH_ID)?.failedCount)
    }

    @Test
    fun transientFailureAfterCancellationCancelsInsteadOfRequeueing() = runBlocking {
        createBatch(itemCount = 2)
        val current = requireNotNull(dao.claimNextItem(BATCH_ID))
        dao.requestCancellation(BATCH_ID)

        assertTrue(
            dao.requeueOrFailProcessingItem(
                itemId = current.id,
                batchId = BATCH_ID,
                maxAttempts = 3,
                message = "Provider unavailable."
            )
        )

        val batch = requireNotNull(dao.getBatch(BATCH_ID))
        assertEquals(BatchState.Cancelled, batch.state)
        assertEquals(2, batch.cancelledCount)
        assertTrue(dao.getItems(BATCH_ID).all { it.state == BatchItemState.Cancelled })
    }

    @Test
    fun secondUnfinishedBatchIsRejectedTransactionally() = runBlocking {
        createBatch(batchId = "first", itemCount = 1, createdAt = 1L)

        val failure = runCatching {
            createBatch(batchId = "second", itemCount = 1, createdAt = 2L)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("first"), dao.getActiveBatchIds())
    }

    @Test
    fun workerRetryRequeuesItemAndPersistsRetryState() = runBlocking {
        createBatch(itemCount = 1)
        requireNotNull(dao.claimNextItem(BATCH_ID))

        dao.recoverInterruptedItems(BATCH_ID, maxAttempts = 3)
        dao.markForWorkerRetry(BATCH_ID, error = "Worker stopped.")

        val batch = requireNotNull(dao.getBatch(BATCH_ID))
        assertEquals(BatchState.Retrying, batch.state)
        assertEquals("Worker stopped.", batch.lastError)
        assertEquals(BatchItemState.Queued, dao.getItems(BATCH_ID).single().state)
    }

    @Test
    fun blockedBatchCanReplaceDestinationAndResume() = runBlocking {
        createBatch(itemCount = 1)
        dao.markNeedsDestination(BATCH_ID, "Choose another folder.")

        assertEquals(1, dao.replaceDestination(BATCH_ID, "content://new-destination"))

        val batch = requireNotNull(dao.getBatch(BATCH_ID))
        assertEquals(BatchState.Queued, batch.state)
        assertEquals("content://new-destination", batch.destinationUri)
        assertEquals(null, batch.lastError)
    }

    @Test
    fun completedItemPersistsSavedBytes() = runBlocking {
        createBatch(itemCount = 1)
        val item = requireNotNull(dao.claimNextItem(BATCH_ID))

        dao.completeItem(
            itemId = item.id,
            batchId = BATCH_ID,
            state = BatchItemState.Compressed,
            outputUri = "content://output/1",
            outputBytes = 60L,
            message = null
        )

        assertEquals(40L, dao.getBatch(BATCH_ID)?.totalSavedBytes)
    }

    @Test
    fun repeatedlyInterruptedItemFailsAtAttemptLimit() = runBlocking {
        createBatch(itemCount = 2)
        val interrupted = requireNotNull(dao.claimNextItem(BATCH_ID))
        dao.recoverInterruptedItems(BATCH_ID, maxAttempts = 2)
        requireNotNull(dao.claimNextItem(BATCH_ID))

        assertEquals(1, dao.recoverInterruptedItems(BATCH_ID, maxAttempts = 2))
        val next = requireNotNull(dao.claimNextItem(BATCH_ID))

        assertEquals(0, interrupted.position)
        assertEquals(1, next.position)
        assertEquals(BatchItemState.Failed, dao.getItems(BATCH_ID).first().state)
        assertEquals(1, dao.getBatch(BATCH_ID)?.failedCount)
    }

    @Test
    fun cancellationPersistsAndCancelsAllQueuedItems() = runBlocking {
        createBatch(itemCount = 3)

        assertTrue(dao.requestCancellation(BATCH_ID))

        val batch = requireNotNull(dao.getBatch(BATCH_ID))
        assertEquals(BatchState.Cancelled, batch.state)
        assertTrue(batch.cancellationRequested)
        assertEquals(3, batch.completedCount)
        assertEquals(3, batch.cancelledCount)
        assertTrue(dao.getItems(BATCH_ID).all { it.state == BatchItemState.Cancelled })
    }

    @Test
    fun cancellationLeavesCurrentItemToFinishThenBatchFinalizes() = runBlocking {
        createBatch(itemCount = 2)
        val current = requireNotNull(dao.claimNextItem(BATCH_ID))

        assertTrue(dao.requestCancellation(BATCH_ID))
        assertEquals(BatchState.Cancelling, dao.getBatch(BATCH_ID)?.state)
        assertEquals(BatchItemState.Cancelled, dao.getItems(BATCH_ID)[1].state)

        dao.completeItem(
            itemId = current.id,
            batchId = BATCH_ID,
            state = BatchItemState.CopiedOriginal,
            outputUri = "content://output/1",
            outputBytes = 100L,
            message = null
        )
        dao.finishBatch(BATCH_ID)

        val batch = requireNotNull(dao.getBatch(BATCH_ID))
        assertEquals(BatchState.Cancelled, batch.state)
        assertEquals(2, batch.completedCount)
        assertEquals(1, batch.copiedCount)
        assertEquals(1, batch.cancelledCount)
    }

    @Test
    fun interruptedItemIsCancelledWhenCancellationWasPersisted() = runBlocking {
        createBatch(itemCount = 2)
        requireNotNull(dao.claimNextItem(BATCH_ID))
        dao.requestCancellation(BATCH_ID)

        assertEquals(1, dao.recoverInterruptedItems(BATCH_ID, maxAttempts = 3))
        dao.finishBatch(BATCH_ID)

        val batch = requireNotNull(dao.getBatch(BATCH_ID))
        assertEquals(BatchState.Cancelled, batch.state)
        assertEquals(2, batch.cancelledCount)
        assertTrue(dao.getItems(BATCH_ID).all { it.state == BatchItemState.Cancelled })
    }

    @Test
    fun workerFailureDoesNotOverwriteCancellation() = runBlocking {
        createBatch(itemCount = 2)
        requireNotNull(dao.claimNextItem(BATCH_ID))
        dao.requestCancellation(BATCH_ID)

        dao.failBatch(BATCH_ID, "Worker failed.")

        val batch = requireNotNull(dao.getBatch(BATCH_ID))
        assertEquals(BatchState.Cancelled, batch.state)
        assertTrue(batch.cancellationRequested)
        assertEquals(2, batch.cancelledCount)
    }

    @Test
    fun finishingDoesNotOverwriteTerminalFailure() = runBlocking {
        createBatch(itemCount = 1)
        dao.failBatch(BATCH_ID, "Worker failed.")

        dao.finishBatch(BATCH_ID)

        val batch = requireNotNull(dao.getBatch(BATCH_ID))
        assertEquals(BatchState.Failed, batch.state)
        assertEquals("Worker failed.", batch.lastError)
    }

    private suspend fun createBatch(
        batchId: String = BATCH_ID,
        itemCount: Int,
        createdAt: Long = 1L,
        prepared: Boolean = true
    ) {
        val batch = BatchEntity(
            id = batchId,
            createdAt = createdAt,
            quality = 80,
            destinationUri = "content://destination",
            totalCount = itemCount
        )
        val items = List(itemCount) { position ->
            BatchItemEntity(
                id = "$batchId-item-$position",
                batchId = batchId,
                position = position,
                sourceUri = "content://source/$position",
                inputName = if (prepared) "image-$position.jpg" else null,
                outputName = if (prepared) "image-$position.jpg" else null,
                inputBytes = if (prepared) 100L else -1L
            )
        }
        dao.createBatch(batch, items)
    }

    private companion object {
        const val BATCH_ID = "batch-1"
    }
}
