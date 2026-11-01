package com.example.slicer.batch

import android.content.Context
import androidx.core.net.toUri
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.isJpegFileName
import com.example.slicer.processing.JpegDimensions
import com.example.slicer.processing.PreparedBatchOutput
import com.example.slicer.processing.processBatchItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import java.io.File

internal class EncodedAhead(val output: PreparedBatchOutput, val sourceDimensions: JpegDimensions?)

/**
 * Compresses upcoming JPEGs in parallel while the worker still claims, publishes and records one
 * photo at a time. Anything that didn't compress cleanly is dropped and processed inline instead.
 */
internal class EncodeAhead(private val context: Context, val encoders: Int) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val directory = File(context.noBackupFilesDir, "encode-ahead").apply {
        deleteRecursively()
        mkdirs()
    }
    private val pending = mutableMapOf<String, Deferred<EncodedAhead?>>()

    // Each encoder plus a possible inline retry gets an equal share of native memory.
    val budgetShares = encoders + 1

    fun schedule(upcoming: List<BatchItemEntity>, batch: BatchEntity, current: BatchItemEntity) {
        val stillUpcoming = upcoming.mapTo(mutableSetOf()) { it.id } + current.id
        pending.keys.filter { it !in stillUpcoming }.forEach { id -> pending.remove(id)?.cancel() }
        upcoming
            .filter { it.id !in pending && it.outputName?.let(::isJpegFileName) == true }
            .take(encoders - pending.size)
            .forEach { item -> pending[item.id] = scope.async { encode(item, batch) } }
    }

    suspend fun take(item: BatchItemEntity, destination: File): EncodedAhead? {
        val ready = pending.remove(item.id)?.takeUnless { it.isCancelled }?.await() ?: return null
        return ready.takeIf { fileFor(item).renameTo(destination) }
    }

    suspend fun close() {
        withContext(NonCancellable) { job.cancelAndJoin() }
        directory.deleteRecursively()
    }

    private suspend fun encode(item: BatchItemEntity, batch: BatchEntity): EncodedAhead? {
        val file = fileFor(item)
        var sourceDimensions: JpegDimensions? = null
        return try {
            val output = processBatchItem(
                context = context,
                inputUri = item.sourceUri.toUri(),
                tempFile = file,
                quality = batch.quality,
                inputBytes = item.inputBytes,
                knownDimensions = item.width.takeIf { it > 0 }?.let { JpegDimensions(item.width, item.height) },
                onDimensionsRead = { sourceDimensions = it },
                maxDimension = batch.maxDimension,
                budgetShares = budgetShares
            )
            if (output.decision == ProcessingDecision.Compressed) {
                EncodedAhead(output, sourceDimensions)
            } else {
                file.delete()
                null
            }
        } catch (cancelled: CancellationException) {
            file.delete()
            throw cancelled
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun fileFor(item: BatchItemEntity) = File(directory, "${item.id.filter(Char::isLetterOrDigit)}.jpg")
}
