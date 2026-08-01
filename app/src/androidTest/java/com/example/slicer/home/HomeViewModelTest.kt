package com.example.slicer.home

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.slicer.SlicerApplication
import com.example.slicer.batch.BatchEntity
import com.example.slicer.batch.BatchItemEntity
import com.example.slicer.batch.BatchItemState
import com.example.slicer.batch.BatchState
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class HomeViewModelTest {
    private lateinit var application: SlicerApplication

    @Before
    fun setUp() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        application.database.clearAllTables()
    }

    @After
    fun tearDown() = runBlocking {
        application.database.clearAllTables()
    }

    @Test
    fun activeRestoredBatchCannotBeDismissed() = runBlocking {
        application.database.batchDao().createBatch(
            BatchEntity(
                id = BATCH_ID,
                createdAt = 1L,
                quality = 80,
                destinationUri = "content://missing-destination",
                state = BatchState.NeedsDestination,
                totalCount = 1
            ),
            listOf(
                BatchItemEntity(
                    id = "$BATCH_ID-item",
                    batchId = BATCH_ID,
                    position = 0,
                    sourceUri = "content://source"
                )
            )
        )
        val viewModel = HomeViewModel(application)

        withTimeout(5_000) { viewModel.uiState.first { it.batchId == BATCH_ID } }
        viewModel.dismissCurrentBatch()
        delay(100)

        assertEquals(BATCH_ID, viewModel.uiState.value.batchId)
    }

    @Test
    fun startingNewBatchDoesNotRestoreDismissedResults() = runBlocking {
        application.database.batchDao().createBatch(
            BatchEntity(
                id = BATCH_ID,
                createdAt = 1L,
                completedAt = 2L,
                quality = 80,
                destinationUri = "content://destination",
                state = BatchState.Completed,
                totalCount = 1,
                completedCount = 1,
                compressedCount = 1,
                totalInputBytes = 100L,
                totalOutputBytes = 50L,
                totalSavedBytes = 50L
            ),
            listOf(
                BatchItemEntity(
                    id = "$BATCH_ID-item",
                    batchId = BATCH_ID,
                    position = 0,
                    sourceUri = "content://source/old",
                    inputName = "old.jpg",
                    outputName = "old.jpg",
                    outputUri = "content://output/old",
                    state = BatchItemState.Compressed,
                    inputBytes = 100L,
                    outputBytes = 50L
                )
            )
        )
        val viewModel = HomeViewModel(application)
        val observedIds = CopyOnWriteArrayList<String?>()
        val collection = launch { viewModel.uiState.collect { state -> observedIds += state.batchId } }

        try {
            withTimeout(5_000) { viewModel.uiState.first { it.batchId == BATCH_ID } }
            viewModel.dismissCurrentBatch()
            withTimeout(5_000) { viewModel.uiState.first { it.batchId == null } }
            observedIds.clear()

            viewModel.startBatch(
                sourceUris = listOf(android.net.Uri.parse("content://source/no-persisted-grant")),
                destinationUri = android.net.Uri.parse("content://destination"),
                quality = 80
            )
            withTimeout(5_000) {
                viewModel.uiState.first { state -> state.batchId != null && state.batchId != BATCH_ID }
            }
            delay(100)

            assertEquals(false, observedIds.contains(BATCH_ID))
        } finally {
            collection.cancelAndJoin()
        }
    }

    private companion object {
        const val BATCH_ID = "active-restored-batch"
    }
}
