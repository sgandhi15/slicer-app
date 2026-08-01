package com.example.slicer.processing

import com.example.slicer.core.MOZJPEG_SUBSAMPLING_420
import com.example.slicer.core.MOZJPEG_SUBSAMPLING_422
import com.example.slicer.core.MOZJPEG_SUBSAMPLING_444
import com.example.slicer.storage.loadBitmapForCompression
import com.example.slicer.telemetry.logMozJpegEventTelemetry

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

internal data class JpegCompressionResult(
    val succeeded: Boolean,
    val message: String? = null,
    val dimensions: JpegDimensions? = null
)

internal suspend fun Context.compressJpegImage(
    inputUri: Uri,
    outputFile: File,
    quality: Int
): JpegCompressionResult = withContext(Dispatchers.IO) {
    runCatching {
        val coroutineContext = currentCoroutineContext()
        val headerInspection = contentResolver.openInputStream(inputUri)?.use { input ->
            inspectJpegHeader(input, checkpoint = coroutineContext::ensureActive)
        }
            ?: return@runCatching JpegCompressionResult(
                succeeded = false,
                message = "Could not read JPEG dimensions; original file was preserved."
            )
        val dimensions = when (headerInspection) {
            is JpegInspectionResult.Valid -> headerInspection.structure.dimensions
            is JpegInspectionResult.Invalid -> {
                return@runCatching JpegCompressionResult(
                    succeeded = false,
                    message = "JPEG structure was invalid; original file was preserved."
                )
            }
        }

        val memoryDecision = evaluateEncodingMemory(dimensions.width, dimensions.height)
        if (!memoryDecision.canEncode) {
            val message = when (memoryDecision.rejection) {
                EncodingMemoryRejection.ImageTooLarge ->
                    "Image is too large to compress safely (${dimensions.width} x ${dimensions.height}); original file was preserved."

                else ->
                    "Not enough memory is available to compress this image safely (${dimensions.width} x ${dimensions.height}); original file was preserved."
            }
            return@runCatching JpegCompressionResult(
                succeeded = false,
                message = message,
                dimensions = dimensions
            )
        }

        val bitmap = loadBitmapForCompression(inputUri)
            ?: return@runCatching JpegCompressionResult(
                succeeded = false,
                message = "Unable to decode JPEG data; original file was preserved.",
                dimensions = dimensions
            )
        try {
            if (bitmap.width != dimensions.width || bitmap.height != dimensions.height) {
                return@runCatching JpegCompressionResult(
                    succeeded = false,
                    message = "JPEG dimensions changed while it was being read; original file was preserved.",
                    dimensions = dimensions
                )
            }

            val encoded = bitmap.encodeJpegViaMozJpeg(outputFile, quality)
            if (!encoded) {
                return@runCatching JpegCompressionResult(
                    succeeded = false,
                    message = "MozJPEG compression failed; original file was preserved.",
                    dimensions = dimensions
                )
            }

            currentCoroutineContext().ensureActive()
            JpegCompressionResult(succeeded = true, dimensions = dimensions)
        } finally {
            bitmap.recycle()
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        JpegCompressionResult(
            succeeded = false,
            message = if (error is OutOfMemoryError) {
                "The image exceeded the available memory while compressing; original file was preserved."
            } else {
                error.message ?: "MozJPEG compression failed; original file was preserved."
            }
        )
    }
}

private data class MozJpegAttempt(
    val subsampling: Int,
    val progressive: Boolean,
    val accurateDct: Boolean
) {
    val describe: String
        get() = buildString {
            append("subsampling=")
            append(subsampling)
            append(", progressive=")
            append(progressive)
            append(", accurateDct=")
            append(accurateDct)
        }
}

private suspend fun Bitmap.encodeJpegViaMozJpeg(outputFile: File, quality: Int): Boolean {
    if (!MozJpegNative.isAvailable) {
        logMozJpegEventTelemetry("MozJPEG native module unavailable; ${MozJpegNative.runtimeStatusMessage()}.")
        return false
    }

    val attempts = listOf(
        MozJpegAttempt(subsampling = MOZJPEG_SUBSAMPLING_420, progressive = true, accurateDct = true),
        MozJpegAttempt(subsampling = MOZJPEG_SUBSAMPLING_420, progressive = true, accurateDct = false),
        MozJpegAttempt(subsampling = MOZJPEG_SUBSAMPLING_422, progressive = true, accurateDct = true),
        MozJpegAttempt(subsampling = MOZJPEG_SUBSAMPLING_420, progressive = false, accurateDct = true),
        MozJpegAttempt(subsampling = MOZJPEG_SUBSAMPLING_444, progressive = true, accurateDct = true),
        MozJpegAttempt(subsampling = MOZJPEG_SUBSAMPLING_444, progressive = false, accurateDct = true)
    )

    val coroutineContext = currentCoroutineContext()
    var failureReason: String? = null
    for ((attemptIndex, attempt) in attempts.withIndex()) {
        currentCoroutineContext().ensureActive()
        val encoded = runCatching {
            ParcelFileDescriptor.open(outputFile, OUTPUT_FILE_MODE).use { descriptor ->
                MozJpegNative.compressBitmapToJpeg(
                    bitmap = this,
                    outputFileDescriptor = descriptor.fd,
                    quality = quality,
                    progressive = attempt.progressive,
                    subsampling = attempt.subsampling,
                    accurateDct = attempt.accurateDct
                )
            }
        }.getOrDefault(false)
        currentCoroutineContext().ensureActive()

        if (!encoded) {
            failureReason = "${attempt.describe} returned no output"
            continue
        }

        val inspection = try {
            outputFile.inspectCompleteJpeg(
                expectedDimensions = JpegDimensions(width = width, height = height),
                checkpoint = coroutineContext::ensureActive
            )
        } catch (error: Exception) {
            failureReason = "${attempt.describe} could not be inspected: ${error.message}"
            continue
        }
        if (inspection is JpegInspectionResult.Invalid) {
            failureReason = "${attempt.describe} failed structural validation: ${inspection.reason}"
            continue
        }
        inspection as JpegInspectionResult.Valid

        val requiresVisualValidation = requiresVisualJpegValidation(
            isPrimaryAttempt = attemptIndex == 0,
            progressive = attempt.progressive,
            structure = inspection.structure
        )
        if (requiresVisualValidation &&
            !outputFile.isVisuallyConsistentJpeg(width, height, this)
        ) {
            failureReason = "${attempt.describe} failed exceptional visual validation"
            continue
        }

        if (attemptIndex != 0) {
            logMozJpegEventTelemetry("MozJPEG native output succeeded using alternate attempt: ${attempt.describe}")
        }

        return true
    }

    logMozJpegEventTelemetry("MozJPEG encoding failed; $failureReason.")

    return false
}

internal fun requiresVisualJpegValidation(
    isPrimaryAttempt: Boolean,
    progressive: Boolean,
    structure: JpegStructure
): Boolean {
    val expectedFrameMarker = if (progressive) {
        JPEG_PROGRESSIVE_FRAME_MARKER
    } else {
        JPEG_BASELINE_FRAME_MARKER
    }
    return !isPrimaryAttempt ||
        structure.frameMarker != expectedFrameMarker ||
        structure.precision != 8 ||
        structure.componentCount != 3
}

private const val OUTPUT_FILE_MODE = ParcelFileDescriptor.MODE_WRITE_ONLY or
    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
