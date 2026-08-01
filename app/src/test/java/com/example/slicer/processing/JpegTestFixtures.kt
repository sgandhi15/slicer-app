package com.example.slicer.processing

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class JpegTestScan(
    val componentIds: IntArray = intArrayOf(1, 2, 3),
    val entropy: ByteArray = byteArrayOf(0x11, 0x22, 0x33),
    val followingSegments: List<ByteArray> = emptyList()
)

internal fun testJpeg(
    width: Int = 32,
    height: Int = 24,
    frameMarker: Int = JPEG_BASELINE_FRAME_MARKER,
    headerSegments: List<ByteArray> = emptyList(),
    scans: List<JpegTestScan> = listOf(JpegTestScan()),
    trailingBytes: ByteArray = byteArrayOf()
): ByteArray {
    val output = ByteArrayOutputStream()
    output.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    headerSegments.forEach(output::write)
    output.write(frameSegment(frameMarker, width, height))
    scans.forEach { scan ->
        output.write(scanSegment(scan.componentIds))
        output.write(scan.entropy)
        scan.followingSegments.forEach(output::write)
    }
    output.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    output.write(trailingBytes)
    return output.toByteArray()
}

internal fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
    require(marker ushr 8 == 0xFF)
    require(payload.size <= 65_533)
    val length = payload.size + 2
    return byteArrayOf(
        0xFF.toByte(),
        (marker and 0xFF).toByte(),
        (length ushr 8).toByte(),
        length.toByte()
    ) + payload
}

internal fun markerIndex(bytes: ByteArray, marker: Int, startIndex: Int = 0): Int {
    val high = (marker ushr 8).toByte()
    val low = marker.toByte()
    for (index in startIndex until bytes.lastIndex) {
        if (bytes[index] == high && bytes[index + 1] == low) return index
    }
    return -1
}

internal class ShortAndZeroReadInputStream(
    private val bytes: ByteArray
) : InputStream() {
    private var position = 0
    private var bulkReadCount = 0

    override fun read(): Int {
        if (position >= bytes.size) return -1
        return bytes[position++].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        bulkReadCount++
        if (bulkReadCount % 3 == 0) return 0
        val readCount = minOf(length, 3, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + readCount)
        position += readCount
        return readCount
    }
}

private fun frameSegment(marker: Int, width: Int, height: Int): ByteArray {
    return jpegSegment(
        marker,
        byteArrayOf(
            8,
            (height ushr 8).toByte(),
            height.toByte(),
            (width ushr 8).toByte(),
            width.toByte(),
            3,
            1, 0x11, 0,
            2, 0x11, 0,
            3, 0x11, 0
        )
    )
}

private fun scanSegment(componentIds: IntArray): ByteArray {
    val payload = ByteArray(1 + componentIds.size * 2 + 3)
    payload[0] = componentIds.size.toByte()
    componentIds.forEachIndexed { index, componentId ->
        payload[1 + index * 2] = componentId.toByte()
        payload[2 + index * 2] = 0
    }
    payload[payload.size - 3] = 0
    payload[payload.size - 2] = 63
    payload[payload.size - 1] = 0
    return jpegSegment(0xFFDA, payload)
}
