package com.example.slicer.processing

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * The libjpeg-style quality (1-100) a JPEG was saved at, estimated from its luminance quantization
 * table. Returns null when the header has no usable table.
 */
internal fun estimateJpegQuality(input: InputStream): Int? {
    val data = DataInputStream(input.buffered())
    if (data.readUnsignedShort() != 0xFFD8) return null
    while (true) {
        var marker = data.readUnsignedByte()
        if (marker != 0xFF) return null
        while (marker == 0xFF) marker = data.readUnsignedByte()
        if (marker == 0xDA || marker == 0xD9) return null
        val length = data.readUnsignedShort() - 2
        if (marker != 0xDB) {
            data.skipFully(length)
            continue
        }
        var remaining = length
        while (remaining > 0) {
            val info = data.readUnsignedByte()
            val sixteenBit = info shr 4 != 0
            val table = IntArray(64) { if (sixteenBit) data.readUnsignedShort() else data.readUnsignedByte() }
            remaining -= 1 + if (sixteenBit) 128 else 64
            if (info and 0x0F == 0) return qualityFromLuminanceTable(table)
        }
    }
}

private fun DataInputStream.skipFully(count: Int) {
    var left = count
    while (left > 0) {
        val skipped = skipBytes(left)
        if (skipped <= 0) {
            readUnsignedByte()
            left--
        } else {
            left -= skipped
        }
    }
}

// Inverts libjpeg's scaling of the standard table: entry = (standard * scale + 50) / 100.
private fun qualityFromLuminanceTable(zigzagTable: IntArray): Int {
    val scale = zigzagTable.sum() * 100.0 / STANDARD_LUMINANCE_ZIGZAG.sum()
    val quality = if (scale <= 100.0) (200.0 - scale) / 2.0 else 5000.0 / scale
    return quality.roundToInt().coerceIn(1, 100)
}

private val STANDARD_LUMINANCE_ZIGZAG = intArrayOf(
    16, 11, 12, 14, 12, 10, 16, 14, 13, 14, 18, 17, 16, 19, 24, 40,
    26, 24, 22, 22, 24, 49, 35, 37, 29, 40, 58, 51, 61, 60, 57, 51,
    56, 55, 64, 72, 92, 78, 64, 68, 87, 69, 55, 56, 80, 109, 81, 87,
    95, 98, 103, 104, 103, 62, 77, 113, 121, 112, 100, 120, 92, 101, 103, 99
)
