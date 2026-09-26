package com.example.slicer.batch

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.slicer.SlicerApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchWorkerTest {
    private lateinit var application: SlicerApplication
    private lateinit var dao: BatchDao

    @Before
    fun setUp() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        application.database.clearAllTables()
        dao = application.database.batchDao()
    }

    @After
    fun tearDown() = runBlocking {
        application.database.clearAllTables()
    }

    @Test
    fun succeedsWhenNoBatchesAreActive() = runBlocking {
        val result = buildWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun inaccessibleDestinationPausesBatch() = runBlocking {
        createBatch(BATCH_ID)

        val result = buildWorker().doWork()

        val batch = requireNotNull(dao.getBatch(BATCH_ID))
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(BatchState.NeedsDestination, batch.state)
        assertEquals(
            "Output folder is no longer accessible. Choose a new folder to continue.",
            batch.lastError
        )
        assertEquals(0, batch.failedCount)
        assertEquals(BatchItemState.Queued, dao.getItems(BATCH_ID).single().state)
    }

    @Test
    fun cancellationBeforeWorkerStartsFinalizesBatch() = runBlocking {
        createBatch(BATCH_ID)
        dao.requestCancellation(BATCH_ID)

        val result = buildWorker().doWork()

        val batch = requireNotNull(dao.getBatch(BATCH_ID))
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(BatchState.Cancelled, batch.state)
        assertEquals(1, batch.cancelledCount)
    }

    @Test
    fun repeatedWorkerInterruptionsPreserveUntouchedQueueAtTheRetryLimit() = runBlocking {
        createBatch(BATCH_ID)

        val result = buildWorker(runAttemptCount = 3).doWork()

        val batch = requireNotNull(dao.getBatch(BATCH_ID))
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(BatchState.Retrying, batch.state)
        assertEquals(0, batch.failedCount)
        assertEquals(BatchItemState.Queued, dao.getItems(BATCH_ID).single().state)
    }

    private fun buildWorker(runAttemptCount: Int = 1): BatchWorker {
        return TestListenableWorkerBuilder<BatchWorker>(
            context = application,
            inputData = workDataOf(BatchWorker.BATCH_ID_KEY to BATCH_ID),
            runAttemptCount = runAttemptCount
        ).build()
    }

    private suspend fun createBatch(batchId: String) {
        val batch = BatchEntity(
            id = batchId,
            createdAt = 1L,
            quality = 80,
            destinationUri = "content://destination",
            totalCount = 1
        )
        val item = BatchItemEntity(
            id = "$batchId-item-0",
            batchId = batchId,
            position = 0,
            sourceUri = "file:///data/local/tmp/source-0.jpg",
            inputName = "image-0.jpg",
            outputName = "image-0.jpg",
            inputBytes = 100L
        )
        dao.createBatch(batch, listOf(item))
    }

    private companion object {
        const val BATCH_ID = "worker-batch"
    }
}
