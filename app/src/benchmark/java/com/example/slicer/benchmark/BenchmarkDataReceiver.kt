package com.example.slicer.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.slicer.SlicerApplication
import com.example.slicer.batch.BatchEntity
import com.example.slicer.batch.BatchItemEntity
import com.example.slicer.batch.BatchItemState
import com.example.slicer.batch.BatchState
import com.example.slicer.storage.markWelcomeScreenSeen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class BenchmarkDataReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SEED_ACTION) return
        val resultCount = intent.getIntExtra(RESULT_COUNT_EXTRA, 0)
        require(resultCount in 1..MAX_BENCHMARK_RESULTS)

        val application = context.applicationContext as SlicerApplication
        runBlocking(Dispatchers.IO) {
            application.database.clearAllTables()
            application.database.batchDao().createBatch(
                batch = BatchEntity(
                    id = BENCHMARK_BATCH_ID,
                    createdAt = 1L,
                    completedAt = 2L,
                    quality = 80,
                    destinationUri = "content://benchmark/destination",
                    state = BatchState.Completed,
                    totalCount = resultCount
                ),
                items = List(resultCount) { position ->
                    BatchItemEntity(
                        id = "benchmark-item-$position",
                        batchId = BENCHMARK_BATCH_ID,
                        position = position,
                        sourceUri = "content://benchmark/source/$position.jpg",
                        inputName = "Image $position.jpg",
                        outputName = "Image $position.jpg",
                        outputUri = "content://benchmark/output/$position.jpg",
                        state = BatchItemState.Compressed,
                        attemptCount = 1,
                        inputBytes = 1_000_000L,
                        outputBytes = 600_000L
                    )
                }
            )
        }
        context.markWelcomeScreenSeen()
        resultCode = Activity.RESULT_OK
        resultData = "seeded=$resultCount"
    }

    private companion object {
        const val SEED_ACTION = "com.example.slicer.SEED_BENCHMARK_RESULTS"
        const val RESULT_COUNT_EXTRA = "result_count"
        const val BENCHMARK_BATCH_ID = "benchmark-results"
        const val MAX_BENCHMARK_RESULTS = 1_000
    }
}
