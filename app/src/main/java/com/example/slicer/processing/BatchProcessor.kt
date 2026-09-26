package com.example.slicer.processing

import com.example.slicer.core.MAX_QUALITY
import com.example.slicer.core.MIN_QUALITY
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.RetryableFailure
import com.example.slicer.storage.copyUriContent
import com.example.slicer.telemetry.MeasuredInputStream
import com.example.slicer.telemetry.ProcessingMetricsRecorder
import com.example.slicer.telemetry.ProcessingStage

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
internal class SourceChangedException(message: String) : IOException(message), RetryableFailure
internal class ValidationResourceException(message: String) : IOException(message), RetryableFailure

internal suspend fun processBatchItem(
    context: Context,
    inputUri: Uri,
    tempFile: File,
    quality: Int,
    inputBytes: Long,
    knownDimensions: JpegDimensions? = null,
    onDimensionsRead: suspend (JpegDimensions) -> Unit = {},
    metrics: ProcessingMetricsRecorder = ProcessingMetricsRecorder.Disabled
): PreparedBatchOutput = withContext(Dispatchers.IO) {
    val result = processSingleImage(
        context = context,
        inputUri = inputUri,
        outputFile = tempFile,
        quality = quality,
        inputBytes = inputBytes,
        knownDimensions = knownDimensions,
        onDimensionsRead = onDimensionsRead,
        metrics = metrics
    )
    val actualBytes = tempFile.length()
    if (!tempFile.isFile || actualBytes <= 0L || actualBytes != result.outputBytes) {
        throw IOException("Prepared output size failed final validation.")
    }
    val coroutineContext = currentCoroutineContext()
    var validationBytesRead = 0L
    val inspection = try {
        metrics.measure(ProcessingStage.FinalValidation) {
            tempFile.inputStream().use { input ->
                val measured = MeasuredInputStream(input)
                try {
                    inspectCompleteJpeg(
                        input = measured,
                        expectedDimensions = result.expectedDimensions,
                        allowTrailingBytes = result.decision == ProcessingDecision.CopiedOriginal && inputBytes > 0L,
                        checkpoint = coroutineContext::ensureActive
                    )
                } finally {
                    validationBytesRead = measured.bytesRead
                }
            }
        }
    } finally {
        metrics.recordIo(ProcessingStage.FinalValidation, bytesRead = validationBytesRead)
    }
    if (inspection is JpegInspectionResult.Invalid) {
        val message = "Prepared output is not a complete JPEG: ${inspection.reason}"
        if (result.decision == ProcessingDecision.CopiedOriginal && inputBytes <= 0L) {
            throw IOException(message)
        }
        throw InvalidJpegDataException(message)
    }
    if (result.decision == ProcessingDecision.CopiedOriginal) {
        inspection as JpegInspectionResult.Valid
        coroutineContext.ensureActive()
        when (
            tempFile.validateJpegViaMozJpeg(
                expectedDimensions = result.expectedDimensions ?: inspection.structure.dimensions,
                maximumWorkingBytes = context.jpegValidationWorkingMemoryLimitBytes()
            )
        ) {
            NativeJpegValidationResult.Valid -> Unit
            NativeJpegValidationResult.InvalidData -> throw InvalidJpegDataException(
                "Prepared original could not be fully decoded as JPEG data."
            )
            NativeJpegValidationResult.IoFailure -> throw IOException(
                "Prepared original could not be read for final validation."
            )
            NativeJpegValidationResult.ResourceLimit -> {
                throw ValidationResourceException(
                    "Not enough memory was available to validate the original safely."
                )
            }
            NativeJpegValidationResult.Unavailable -> {
                val dimensions = result.expectedDimensions ?: inspection.structure.dimensions
                if (!context.evaluateEncodingMemory(dimensions.width, dimensions.height).canEncode) {
                    throw ValidationResourceException(
                        "Not enough memory was available to validate the original safely."
                    )
                }
                when (tempFile.validateJpegWithPlatformDecoder(dimensions)) {
                    PlatformJpegValidationResult.Valid -> Unit
                    PlatformJpegValidationResult.InvalidData -> throw InvalidJpegDataException(
                        "Prepared original could not be decoded by the platform JPEG decoder."
                    )
                    PlatformJpegValidationResult.IoFailure -> throw IOException(
                        "Prepared original could not be read by the platform JPEG decoder."
                    )
                    PlatformJpegValidationResult.ResourceLimit -> throw ValidationResourceException(
                        "Not enough memory was available to validate the original safely."
                    )
                }
            }
        }
        coroutineContext.ensureActive()
    }
    result.copy(outputBytes = actualBytes)
}

private suspend fun processSingleImage(
    context: Context,
    inputUri: Uri,
    outputFile: File,
    quality: Int,
    inputBytes: Long,
    knownDimensions: JpegDimensions?,
    onDimensionsRead: suspend (JpegDimensions) -> Unit,
    metrics: ProcessingMetricsRecorder
): PreparedBatchOutput {
    val outputUri = outputFile.toUri()
    val q = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)

    val compressionResult = try {
        context.compressJpegImage(
            inputUri = inputUri,
            outputFile = outputFile,
            quality = q,
            knownDimensions = knownDimensions,
            onDimensionsRead = onDimensionsRead,
            metrics = metrics
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (stateFailure: JpegStatePersistenceException) {
        throw stateFailure
    } catch (sourceSafety: JpegSourceSafetyException) {
        throw sourceSafety
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
            expectedDimensions = compressionResult.dimensions,
            metrics = metrics
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
            expectedDimensions = dimensions,
            metrics = metrics
        )
    }

    if (inputBytes > 0L && compressedBytes >= inputBytes) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output was larger than source.",
            expectedDimensions = dimensions,
            metrics = metrics
        )
    }

    val metadataCopied = context.preserveJpegMetadata(
        sourceUri = inputUri,
        encodedFile = outputFile,
        expectedDimensions = dimensions,
        maximumOutputBytes = inputBytes.takeIf { it > 0L },
        metrics = metrics
    )
    if (!metadataCopied) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Metadata could not be copied safely; original file was preserved.",
            expectedDimensions = dimensions,
            metrics = metrics
        )
    }

    val finalBytes = outputFile.length()
    if (inputBytes > 0L && finalBytes >= inputBytes) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output with metadata was larger than source.",
            expectedDimensions = dimensions,
            metrics = metrics
        )
    }

    if (finalBytes <= 0L) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output with metadata was invalid.",
            expectedDimensions = dimensions,
            metrics = metrics
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
    expectedDimensions: JpegDimensions?,
    metrics: ProcessingMetricsRecorder
): PreparedBatchOutput {
    val copiedBytes = metrics.measure(ProcessingStage.OriginalCopy) {
        context.copyUriContent(inputUri, outputUri)
    }
    metrics.recordIo(
        stage = ProcessingStage.OriginalCopy,
        bytesRead = copiedBytes,
        bytesWritten = copiedBytes
    )
    if (inputBytes > 0L && copiedBytes != inputBytes) {
        throw SourceChangedException("Source changed while it was being copied.")
    }

    return PreparedBatchOutput(
        decision = ProcessingDecision.CopiedOriginal,
        outputBytes = copiedBytes,
        message = outputLabel,
        expectedDimensions = expectedDimensions
    )
}
