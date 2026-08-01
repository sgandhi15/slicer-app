package com.example.slicer.processing

import com.example.slicer.core.MAX_QUALITY
import com.example.slicer.core.MIN_QUALITY
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.storage.copyUriContent

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal data class PreparedBatchOutput(
    val decision: ProcessingDecision,
    val outputBytes: Long,
    val message: String?,
    val expectedDimensions: JpegDimensions? = null
)

internal class InvalidJpegDataException(message: String) : Exception(message)

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
        outputFile = tempFile,
        quality = quality,
        inputBytes = inputBytes
    )
    val actualBytes = tempFile.length()
    if (!tempFile.isFile || actualBytes <= 0L || actualBytes != result.outputBytes) {
        throw IOException("Prepared output size failed final validation.")
    }
    val coroutineContext = currentCoroutineContext()
    val inspection = tempFile.inspectCompleteJpeg(
        expectedDimensions = result.expectedDimensions,
        allowTrailingBytes = result.decision == ProcessingDecision.CopiedOriginal && inputBytes > 0L,
        checkpoint = coroutineContext::ensureActive
    )
    if (inspection is JpegInspectionResult.Invalid) {
        val message = "Prepared output is not a complete JPEG: ${inspection.reason}"
        if (result.decision == ProcessingDecision.CopiedOriginal && inputBytes <= 0L) {
            throw IOException(message)
        }
        throw InvalidJpegDataException(message)
    }
    result.copy(outputBytes = actualBytes)
}

private suspend fun processSingleImage(
    context: Context,
    inputUri: Uri,
    outputFile: File,
    quality: Int,
    inputBytes: Long
): PreparedBatchOutput {
    val outputUri = outputFile.toUri()
    val q = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)

    val compressionResult = try {
        context.compressJpegImage(inputUri, outputFile, q)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        JpegCompressionResult(
            succeeded = false,
            message = error.message ?: "MozJPEG compression failed; original file was preserved."
        )
    }

    if (!compressionResult.succeeded) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = compressionResult.message ?: "MozJPEG compression failed; original file was preserved.",
            expectedDimensions = compressionResult.dimensions
        )
    }

    val dimensions = checkNotNull(compressionResult.dimensions) {
        "Successful compression did not report JPEG dimensions."
    }
    val compressedBytes = outputFile.length()
    if (compressedBytes <= 0L) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output was invalid.",
            expectedDimensions = dimensions
        )
    }

    if (inputBytes > 0L && compressedBytes > inputBytes) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output was larger than source.",
            expectedDimensions = dimensions
        )
    }

    val metadataCopied = context.preserveJpegMetadata(
        sourceUri = inputUri,
        encodedFile = outputFile,
        expectedDimensions = dimensions,
        maximumOutputBytes = inputBytes.takeIf { it > 0L }
    )
    if (!metadataCopied) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Metadata could not be copied safely; original file was preserved.",
            expectedDimensions = dimensions
        )
    }

    val finalBytes = outputFile.length()
    if (inputBytes > 0L && finalBytes > inputBytes) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output with metadata was larger than source.",
            expectedDimensions = dimensions
        )
    }

    if (finalBytes <= 0L) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output with metadata was invalid.",
            expectedDimensions = dimensions
        )
    }

    return PreparedBatchOutput(
        decision = ProcessingDecision.Compressed,
        outputBytes = finalBytes,
        message = null,
        expectedDimensions = dimensions
    )
}

private suspend fun fallbackToOriginalOrFail(
    context: Context,
    inputUri: Uri,
    inputBytes: Long,
    outputUri: Uri,
    outputLabel: String,
    expectedDimensions: JpegDimensions?
): PreparedBatchOutput {
    val copiedBytes = context.copyUriContent(inputUri, outputUri)
    if (inputBytes > 0L && copiedBytes != inputBytes) {
        throw IOException("Source changed while it was being copied.")
    }

    return PreparedBatchOutput(
        decision = ProcessingDecision.CopiedOriginal,
        outputBytes = copiedBytes,
        message = outputLabel,
        expectedDimensions = expectedDimensions
    )
}
