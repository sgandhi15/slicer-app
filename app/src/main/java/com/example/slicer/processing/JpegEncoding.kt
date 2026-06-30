package com.example.slicer.processing

import com.example.slicer.core.MOZJPEG_SUBSAMPLING_420
import com.example.slicer.core.MOZJPEG_SUBSAMPLING_422
import com.example.slicer.core.MOZJPEG_SUBSAMPLING_444
import com.example.slicer.storage.loadBitmapForCompression
import com.example.slicer.storage.writeByteArrayToUri
import com.example.slicer.telemetry.logMozJpegEventTelemetry

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal suspend fun Context.compressJpegImage(
    inputUri: Uri,
    outputUri: Uri,
    quality: Int
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap = loadBitmapForCompression(inputUri)
            ?: throw IOException("Unable to decode JPEG data.")
        try {
            val encoded = bitmap.encodeJpegViaMozJpeg(quality)
                ?: throw IOException("MozJPEG compression failed.")

            val saved = writeByteArrayToUri(outputUri, encoded)

            if (!saved) {
                throw IOException("Could not write MozJPEG output.")
            }
        } finally {
            bitmap.recycle()
        }
    }.isSuccess
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

private fun Bitmap.encodeJpegViaMozJpeg(quality: Int): ByteArray? {
    if (!MozJpegNative.isAvailable) {
        logMozJpegEventTelemetry("MozJPEG native module unavailable; ${MozJpegNative.runtimeStatusMessage()}.")
        return null
    }

    val rgbBytes = toRgbByteArray()
    if (rgbBytes.isEmpty()) {
        logMozJpegEventTelemetry("MozJPEG encoding failed; bitmap pixel extraction returned empty buffer.")
        return null
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
            MozJpegNative.compressRgbToJpeg(
                rgbPixels = rgbBytes,
                width = width,
                height = height,
                quality = quality,
                progressive = attempt.progressive,
                subsampling = attempt.subsampling,
                accurateDct = attempt.accurateDct
            )
        }.getOrNull()

        if (encoded == null) {
            failureReason = "${attempt.describe} returned no output"
            continue
        }

        if (!encoded.isLikelyUsableJpeg(width, height, this)) {
            failureReason = "${attempt.describe} failed post-encode validation"
            continue
        }

        if (attempt != attempts.first()) {
            logMozJpegEventTelemetry("MozJPEG native output succeeded using alternate attempt: ${attempt.describe}")
        }

        return encoded
    }

    logMozJpegEventTelemetry("MozJPEG encoding failed; $failureReason.")

    return null
}

private fun Bitmap.toRgbByteArray(): ByteArray {
    if (isRecycled || width <= 0 || height <= 0) {
        return ByteArray(0)
    }

    val normalizedBitmap = if (config == Bitmap.Config.ARGB_8888) {
        this
    } else {
        copy(Bitmap.Config.ARGB_8888, false) ?: this
    }

    val imageWidth = width
    val imageHeight = height
    val argb = IntArray(imageWidth * imageHeight)
    normalizedBitmap.getPixels(argb, 0, imageWidth, 0, 0, imageWidth, imageHeight)

    if (normalizedBitmap !== this) {
        normalizedBitmap.recycle()
    }

    val rgbBytes = ByteArray(argb.size * 3)
    var rgbIndex = 0
    for (color in argb) {
        rgbBytes[rgbIndex++] = ((color ushr 16) and 0xFF).toByte()
        rgbBytes[rgbIndex++] = ((color ushr 8) and 0xFF).toByte()
        rgbBytes[rgbIndex++] = (color and 0xFF).toByte()
    }

    return rgbBytes
}
