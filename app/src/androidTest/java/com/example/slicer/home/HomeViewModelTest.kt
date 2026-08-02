package com.example.slicer.home

import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.slicer.SlicerApplication
import com.example.slicer.batch.BatchEntity
import com.example.slicer.batch.BatchItemEntity
import com.example.slicer.batch.BatchItemState
import com.example.slicer.batch.BatchState
import com.example.slicer.storage.ImageSelectionOutcome
import com.example.slicer.storage.ImageSelectionValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        val viewModel = HomeViewModel(application, SavedStateHandle())

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
        val viewModel = HomeViewModel(application, SavedStateHandle())
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

    @Test
    fun imageSelectionReportsSupportedUnsupportedAndInaccessibleFiles() = runBlocking {
        val supported = Uri.parse("content://selection/supported.jpg")
        val unsupported = Uri.parse("content://selection/unsupported.png")
        val inaccessible = Uri.parse("content://selection/inaccessible.jpg")
        val validator = ImageSelectionValidator { uri ->
            when (uri) {
                supported -> ImageSelectionOutcome.Supported
                unsupported -> ImageSelectionOutcome.Unsupported
                else -> ImageSelectionOutcome.Inaccessible
            }
        }
        val viewModel = HomeViewModel(
            application,
            SavedStateHandle(),
            validator,
            Dispatchers.Default
        )

        viewModel.validateImageSelection(listOf(supported, unsupported, inaccessible, supported))

        val selection = withTimeout(5_000) {
            viewModel.selectionState.first { state -> !state.isValidating && state.totalCount == 3 }
        }
        assertEquals(listOf(supported), selection.selectedImages)
        assertEquals(listOf(unsupported), selection.unsupportedUris)
        assertEquals(listOf(inaccessible), selection.inaccessibleUris)
        assertEquals(3, selection.validatedCount)
    }

    @Test
    fun lateValidationCannotReplaceANewerSelection() = runBlocking {
        val first = Uri.parse("content://selection/first.jpg")
        val second = Uri.parse("content://selection/second.jpg")
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val validator = ImageSelectionValidator { uri ->
            if (uri == first) {
                firstStarted.complete(Unit)
                withContext(NonCancellable) { releaseFirst.await() }
            }
            ImageSelectionOutcome.Supported
        }
        val viewModel = HomeViewModel(
            application,
            SavedStateHandle(),
            validator,
            Dispatchers.Default
        )

        viewModel.validateImageSelection(listOf(first))
        withTimeout(5_000) { firstStarted.await() }
        viewModel.validateImageSelection(listOf(second))
        withTimeout(5_000) {
            viewModel.selectionState.first { state ->
                !state.isValidating && state.selectedImages == listOf(second)
            }
        }
        releaseFirst.complete(Unit)
        delay(100)

        assertEquals(listOf(second), viewModel.selectionState.value.selectedImages)
    }

    @Test
    fun largeSelectionValidationStaysOffMainAndWithinFourWorkers() = runBlocking {
        val uris = List(400) { index -> Uri.parse("content://selection/image-$index.jpg") }
        val activeCalls = AtomicInteger(0)
        val maximumActiveCalls = AtomicInteger(0)
        val calledOnMain = AtomicBoolean(false)
        val validator = ImageSelectionValidator {
            if (Looper.myLooper() == Looper.getMainLooper()) calledOnMain.set(true)
            val active = activeCalls.incrementAndGet()
            maximumActiveCalls.getAndUpdate { current -> maxOf(current, active) }
            try {
                Thread.sleep(2L)
                ImageSelectionOutcome.Supported
            } finally {
                activeCalls.decrementAndGet()
            }
        }
        val viewModel = HomeViewModel(
            application,
            SavedStateHandle(),
            validator
        )

        viewModel.validateImageSelection(uris)
        val selection = withTimeout(15_000) {
            viewModel.selectionState.first { state -> !state.isValidating && state.totalCount == uris.size }
        }

        assertEquals(uris, selection.selectedImages)
        assertEquals(uris.size, selection.validatedCount)
        assertFalse(calledOnMain.get())
        assertTrue(maximumActiveCalls.get() in 1..4)
    }

    private companion object {
        const val BATCH_ID = "active-restored-batch"
    }
}
