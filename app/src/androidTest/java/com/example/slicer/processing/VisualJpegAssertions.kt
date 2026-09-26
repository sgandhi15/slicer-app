package com.example.slicer.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.get
import androidx.core.graphics.scale
import java.io.File

internal fun File.isVisuallyConsistentJpeg(
    expectedWidth: Int,
    expectedHeight: Int,
    sourceBitmap: Bitmap
): Boolean {
    if (!isFile || length() <= 0L) {
        return false
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(absolutePath, bounds)
    if (bounds.outWidth != expectedWidth || bounds.outHeight != expectedHeight ||
        sourceBitmap.width != expectedWidth || sourceBitmap.height != expectedHeight
    ) {
        return false
    }
    return validateDecodedJpeg(
        decoded = runCatching {
            BitmapFactory.decodeFile(
                absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeForComparison(expectedWidth, expectedHeight)
                }
            )
        }.getOrNull(),
        sourceBitmap = sourceBitmap
    )
}

private fun validateDecodedJpeg(
    decoded: Bitmap?,
    sourceBitmap: Bitmap
): Boolean {
    decoded ?: return false
    return try {
        val filteredSource = if (sourceBitmap.width == decoded.width && sourceBitmap.height == decoded.height) {
            sourceBitmap
        } else {
            sourceBitmap.scale(decoded.width, decoded.height, filter = true)
        }
        try {
            sampledRgbMatches(filteredSource, decoded)
        } finally {
            if (filteredSource !== sourceBitmap) filteredSource.recycle()
        }
    } finally {
        decoded.recycle()
    }
}

private fun sampleSizeForComparison(width: Int, height: Int): Int {
    var sampleSize = 1
    while (maxOf(width, height) / sampleSize > MAX_COMPARISON_DIMENSION) sampleSize *= 2
    return sampleSize
}

private fun sampledRgbMatches(source: Bitmap, decoded: Bitmap): Boolean {
    var totalError = 0L
    var sourceRed = 0L
    var sourceGreen = 0L
    var sourceBlue = 0L
    var decodedRed = 0L
    var decodedGreen = 0L
    var decodedBlue = 0L
    var grossMismatchCount = 0
    var sampleCount = 0

    repeat(COMPARISON_GRID_SIZE) { row ->
        val sourceY = gridCoordinate(row, COMPARISON_GRID_SIZE, source.height)
        val decodedY = gridCoordinate(row, COMPARISON_GRID_SIZE, decoded.height)
        repeat(COMPARISON_GRID_SIZE) { column ->
            val sourcePixel = source[gridCoordinate(column, COMPARISON_GRID_SIZE, source.width), sourceY]
            val decodedPixel = decoded[gridCoordinate(column, COMPARISON_GRID_SIZE, decoded.width), decodedY]
            val sourceR = sourcePixel ushr 16 and 0xFF
            val sourceG = sourcePixel ushr 8 and 0xFF
            val sourceB = sourcePixel and 0xFF
            val decodedR = decodedPixel ushr 16 and 0xFF
            val decodedG = decodedPixel ushr 8 and 0xFF
            val decodedB = decodedPixel and 0xFF
            val error = kotlin.math.abs(sourceR - decodedR) +
                kotlin.math.abs(sourceG - decodedG) + kotlin.math.abs(sourceB - decodedB)

            totalError += error
            if (error / 3 > GROSS_PIXEL_ERROR) grossMismatchCount++
            sourceRed += sourceR
            sourceGreen += sourceG
            sourceBlue += sourceB
            decodedRed += decodedR
            decodedGreen += decodedG
            decodedBlue += decodedB
            sampleCount++
        }
    }

    val channelSamples = sampleCount.toLong()
    val meanAbsoluteError = totalError / (channelSamples * 3L)
    val maximumChannelBias = maxOf(
        kotlin.math.abs(sourceRed - decodedRed),
        kotlin.math.abs(sourceGreen - decodedGreen),
        kotlin.math.abs(sourceBlue - decodedBlue)
    ) / channelSamples
    return meanAbsoluteError <= MAX_MEAN_RGB_ERROR &&
        maximumChannelBias <= MAX_CHANNEL_BIAS &&
        grossMismatchCount * 4 <= sampleCount
}

private fun gridCoordinate(index: Int, gridSize: Int, dimension: Int): Int {
    return (((index + 0.5f) * dimension) / gridSize).toInt().coerceIn(0, dimension - 1)
}

private const val MAX_COMPARISON_DIMENSION = 128
private const val COMPARISON_GRID_SIZE = 16
private const val MAX_MEAN_RGB_ERROR = 56L
private const val MAX_CHANNEL_BIAS = 48L
private const val GROSS_PIXEL_ERROR = 96
