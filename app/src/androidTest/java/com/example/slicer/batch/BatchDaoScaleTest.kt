package com.example.slicer.batch

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.slicer.core.LOG_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchDaoScaleTest {
    private lateinit var database: BatchDatabase
    private lateinit var dao: BatchDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BatchDatabase::class.java).build()
        dao = database.batchDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun representativeLargeQueuesCompleteWithExactAggregates() = runBlocking {
        listOf(100, 400, 1_000).forEach { itemCount ->
            database.clearAllTables()
            val batchId = "scale-$itemCount"
            val startedAt = SystemClock.elapsedRealtimeNanos()
            dao.createBatch(
                batch = BatchEntity(
                    id = batchId,
                    createdAt = itemCount.toLong(),
                    quality = 80,
                    destinationUri = "content://scale/destination",
                    totalCount = itemCount
                ),
                items = List(itemCount) { position ->
                    BatchItemEntity(
                        id = "$batchId-item-$position",
                        batchId = batchId,
                        position = position,
                        sourceUri = "content://scale/source/$position",
                        inputName = "image-$position.jpg",
                        outputName = "image-$position.jpg",
                        inputBytes = 100L
                    )
                }
            )

            repeat(itemCount) { expectedPosition ->
                val item = requireNotNull(dao.claimNextItem(batchId))
                assertEquals(expectedPosition, item.position)
                assertTrue(
                    dao.completeItem(
                        itemId = item.id,
                        batchId = batchId,
                        state = BatchItemState.Compressed,
                        outputUri = "content://scale/output/$expectedPosition",
                        outputBytes = 60L,
                        message = null
                    )
                )
            }
            assertEquals(null, dao.claimNextItem(batchId))
            dao.finishBatch(batchId)

            val batch = requireNotNull(dao.getBatch(batchId))
            assertEquals(BatchState.Completed, batch.state)
            assertEquals(itemCount, batch.completedCount)
            assertEquals(itemCount, batch.compressedCount)
            assertEquals(itemCount * 100L, batch.totalInputBytes)
            assertEquals(itemCount * 60L, batch.totalOutputBytes)
            assertEquals(itemCount * 40L, batch.totalSavedBytes)
            Log.i(
                LOG_TAG,
                "DAO scale metrics items=$itemCount elapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L)
            )
        }
    }
}
