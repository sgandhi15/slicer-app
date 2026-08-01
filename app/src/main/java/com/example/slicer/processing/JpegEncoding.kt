package com.example.slicer.processing

import com.example.slicer.core.MOZJPEG_SUBSAMPLING_420
import com.example.slicer.core.MOZJPEG_SUBSAMPLING_422
import com.example.slicer.core.MOZJPEG_SUBSAMPLING_444
import com.example.slicer.core.MAX_COMPRESSION_PIXELS
import com.example.slicer.storage.loadBitmapForCompression
import com.example.slicer.storage.writeByteArrayToUri
import com.example.slicer.telemetry.logMozJpegEventTelemetry

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class JpegCompressionResult(
    val succeeded: Boolean,
    val message: String? = null
)

private data class ImageBounds(
    val width: Int,
    val height: Int
) {
    val pixels: Long = width.toLong() * height.toLong()
}

internal suspend fun Context.compressJpegImage(
    inputUri: Uri,
    outputUri: Uri,
    quality: Int
): JpegCompressionResult = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = readImageBounds(inputUri)
            ?: return@runCatching JpegCompressionResult(
                succeeded = false,
                message = "Could not read JPEG dimensions; original file was preserved."
            )

        if (bounds.pixels > MAX_COMPRESSION_PIXELS) {
            return@runCatching JpegCompressionResult(
                succeeded = false,
                message = "Image is too large to compress safely (${bounds.width} x ${bounds.height}); original file was preserved."
            )
        }

        val bitmap = loadBitmapForCompression(inputUri)
            ?: return@runCatching JpegCompressionResult(
                succeeded = false,
                message = "Unable to decode JPEG data; original file was preserved."
            )
        try {
            val encoded = bitmap.encodeJpegViaMozJpeg(quality)
                ?: return@runCatching JpegCompressionResult(
                    succeeded = false,
                    message = "MozJPEG compression failed; original file was preserved."
                )

            currentCoroutineContext().ensureActive()
            val saved = writeByteArrayToUri(outputUri, encoded)

            if (!saved) {
                return@runCatching JpegCompressionResult(
                    succeeded = false,
                    message = "Could not write MozJPEG output; original file was preserved."
                )
            }

            JpegCompressionResult(succeeded = true)
        } finally {
            bitmap.recycle()
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        JpegCompressionResult(
            succeeded = false,
            message = error.message ?: "MozJPEG compression failed; original file was preserved."
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
