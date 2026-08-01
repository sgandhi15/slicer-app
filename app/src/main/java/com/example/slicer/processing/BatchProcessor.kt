package com.example.slicer.processing

import com.example.slicer.core.MAX_QUALITY
import com.example.slicer.core.MIN_QUALITY
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.storage.copyUriContent
import com.example.slicer.storage.resolveContentSize

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal data class PreparedBatchOutput(
    val decision: ProcessingDecision,
    val outputBytes: Long,
    val message: String?
)

internal suspend fun processBatchItem(
    context: Context,
    inputUri: Uri,
    tempFile: File,
    quality: Int,
    inputBytes: Long
): PreparedBatchOutput = withContext(Dispatchers.IO) {
    val result = processSingleImage(
        context = context,
        inputUri = inputUri,
        outputUri = tempFile.toUri(),
        quality = quality,
        inputBytes = inputBytes
    )
    val actualBytes = tempFile.length()
    if (!tempFile.isPreparedJpeg(result.outputBytes) || actualBytes <= 0L) {
        throw IOException("Prepared output failed final validation.")
    }
    result.copy(outputBytes = actualBytes)
}

private suspend fun processSingleImage(
    context: Context,
    inputUri: Uri,
    outputUri: Uri,
    quality: Int,
    inputBytes: Long
): PreparedBatchOutput {
    val q = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)

    val compressionResult = try {
        context.compressJpegImage(inputUri, outputUri, q)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        JpegCompressionResult(false, error.message ?: "MozJPEG compression failed; original file was preserved.")
    }

    if (!compressionResult.succeeded) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = compressionResult.message ?: "MozJPEG compression failed; original file was preserved."
        )
    }

    val compressedBytes = context.resolveContentSize(outputUri)
    if (compressedBytes <= 0L) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output was invalid."
        )
    }

    if (inputBytes > 0L && compressedBytes > inputBytes) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output was larger than source."
        )
    }

    val metadataCopied = context.preserveJpegMetadata(inputUri, outputUri)
    if (!metadataCopied) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Metadata could not be copied safely; original file was preserved."
        )
    }

    val finalBytes = context.resolveContentSize(outputUri)
    if (inputBytes > 0L && finalBytes > inputBytes) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output with metadata was larger than source."
        )
    }

    if (finalBytes <= 0L) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output with metadata was invalid."
        )
    }

    return PreparedBatchOutput(
        decision = ProcessingDecision.Compressed,
        outputBytes = finalBytes,
        message = null
    )
}

private suspend fun fallbackToOriginalOrFail(
    context: Context,
    inputUri: Uri,
    inputBytes: Long,
    outputUri: Uri,
    outputLabel: String
): PreparedBatchOutput {
    val copiedBytes = context.copyUriContent(inputUri, outputUri)
    if (inputBytes > 0L && copiedBytes != inputBytes) {
        throw IOException("Source changed while it was being copied.")
    }

    return PreparedBatchOutput(
        decision = ProcessingDecision.CopiedOriginal,
        outputBytes = copiedBytes,
        message = outputLabel
    )
}

private fun File.isPreparedJpeg(expectedBytes: Long): Boolean {
    if (!isFile || length() <= 0L || expectedBytes <= 0L || length() != expectedBytes) return false
    return runCatching {
        inputStream().use { input ->
            input.read() == 0xFF && input.read() == 0xD8
        }
    }.getOrDefault(false)
}
