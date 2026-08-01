package com.example.slicer.processing

import com.example.slicer.core.MOZJPEG_SUBSAMPLING_420
import com.example.slicer.core.MOZJPEG_SUBSAMPLING_422
import com.example.slicer.core.MOZJPEG_SUBSAMPLING_444
import com.example.slicer.storage.loadBitmapForCompression
import com.example.slicer.telemetry.logMozJpegEventTelemetry

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    val message: String? = null
)

private data class ImageBounds(
    val width: Int,
    val height: Int
)

internal suspend fun Context.compressJpegImage(
    inputUri: Uri,
    outputFile: File,
    quality: Int
): JpegCompressionResult = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = readImageBounds(inputUri)
            ?: return@runCatching JpegCompressionResult(
                succeeded = false,
                message = "Could not read JPEG dimensions; original file was preserved."
            )

        val memoryDecision = evaluateEncodingMemory(bounds.width, bounds.height)
        if (!memoryDecision.canEncode) {
            val message = when (memoryDecision.rejection) {
                EncodingMemoryRejection.ImageTooLarge ->
                    "Image is too large to compress safely (${bounds.width} x ${bounds.height}); original file was preserved."

                else ->
                    "Not enough memory is available to compress this image safely (${bounds.width} x ${bounds.height}); original file was preserved."
            }
            return@runCatching JpegCompressionResult(
                succeeded = false,
                message = message
            )
        }

        val bitmap = loadBitmapForCompression(inputUri)
            ?: return@runCatching JpegCompressionResult(
                succeeded = false,
                message = "Unable to decode JPEG data; original file was preserved."
            )
        try {
            val encoded = bitmap.encodeJpegViaMozJpeg(outputFile, quality)
            if (!encoded) {
                return@runCatching JpegCompressionResult(
                    succeeded = false,
                    message = "MozJPEG compression failed; original file was preserved."
                )
            }

            currentCoroutineContext().ensureActive()
            JpegCompressionResult(succeeded = true)
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

private fun Context.readImageBounds(uri: Uri): ImageBounds? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    val decoded = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }.isSuccess

    if (!decoded || options.outWidth <= 0 || options.outHeight <= 0) {
        return null
    }

    return ImageBounds(width = options.outWidth, height = options.outHeight)
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

private fun Bitmap.encodeJpegViaMozJpeg(outputFile: File, quality: Int): Boolean {
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

    var failureReason: String? = null
    for (attempt in attempts) {
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

        if (!encoded) {
            failureReason = "${attempt.describe} returned no output"
            continue
        }

        if (!outputFile.isLikelyUsableJpeg(width, height, this)) {
            failureReason = "${attempt.describe} failed post-encode validation"
            continue
        }

        if (attempt != attempts.first()) {
            logMozJpegEventTelemetry("MozJPEG native output succeeded using alternate attempt: ${attempt.describe}")
        }

        return true
    }

    logMozJpegEventTelemetry("MozJPEG encoding failed; $failureReason.")

    return false
}

private const val OUTPUT_FILE_MODE = ParcelFileDescriptor.MODE_WRITE_ONLY or
    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
