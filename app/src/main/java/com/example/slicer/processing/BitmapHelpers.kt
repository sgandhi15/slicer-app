package com.example.slicer.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.get

internal fun ByteArray.isLikelyUsableJpeg(expectedWidth: Int, expectedHeight: Int, sourceBitmap: Bitmap): Boolean {
    if (isEmpty()) {
        return false
    }

    return runCatching {
        val decoded = BitmapFactory.decodeByteArray(this, 0, size)
            ?: return false

        if (decoded.width != expectedWidth || decoded.height != expectedHeight) {
            decoded.recycle()
            return false
        }

        val sourceSpread = sourceBitmap.sampleLumaRange()
        val decodedSpread = decoded.sampleLumaRange()
        decoded.recycle()

        if (sourceSpread <= 4) {
            true
        } else {
            decodedSpread >= 8 || decodedSpread >= sourceSpread / 4
        }
    }.getOrDefault(false)
}

internal fun Bitmap.sampleLumaRange(): Int {
    val sampleCols = 16
    val sampleRows = 16
    val stepX = maxOf(1, width / sampleCols)
    val stepY = maxOf(1, height / sampleRows)

    var minLuma = 255
    var maxLuma = 0

    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = this[x, y]
            val luma = (((pixel ushr 16) and 0xFF) * 299 +
                ((pixel ushr 8) and 0xFF) * 587 +
                (pixel and 0xFF) * 114) / 1000

            minLuma = minOf(minLuma, luma)
            maxLuma = maxOf(maxLuma, luma)

            x += stepX
        }
        y += stepY
    }

    return maxLuma - minLuma
}
