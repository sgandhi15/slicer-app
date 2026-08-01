package com.example.slicer.batch

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.await as awaitOperation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BatchDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.batchDao()
        workManager = WorkManager.getInstance(context)
        repository = BatchRepository(context, dao, workManager)
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
        val batchId = repository.createAndEnqueueBatch(
            sourceUris = listOf(Uri.parse("content://com.example.slicer.test/no-grant")),
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
                    sourceUri = "content://source"
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
}
