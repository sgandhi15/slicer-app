package com.example.slicer.processing

import java.io.File
import java.io.InputStream

internal const val JPEG_BASELINE_FRAME_MARKER = 0xFFC0
internal const val JPEG_PROGRESSIVE_FRAME_MARKER = 0xFFC2
internal const val MAX_JPEG_HEADER_BYTES = 32L * 1024L * 1024L

internal data class JpegDimensions(
    val width: Int,
    val height: Int
)

internal data class JpegStructure(
    val dimensions: JpegDimensions,
    val frameMarker: Int,
    val precision: Int,
    val componentCount: Int,
    val scanCount: Int,
    val entropyBytes: Long,
    val totalBytes: Long
)

internal sealed interface JpegInspectionResult {
    data class Valid(val structure: JpegStructure) : JpegInspectionResult

    data class Invalid(val reason: String) : JpegInspectionResult
}

internal fun inspectJpegHeader(
    input: InputStream,
    expectedDimensions: JpegDimensions? = null,
    checkpoint: () -> Unit = {}
): JpegInspectionResult {
    return inspectJpeg(
        input = input,
        expectedDimensions = expectedDimensions,
        stopAfterFirstScanHeader = true,
        allowTrailingBytes = true,
        checkpoint = checkpoint
    )
}

internal fun inspectCompleteJpeg(
    input: InputStream,
    expectedDimensions: JpegDimensions? = null,
    allowTrailingBytes: Boolean = false,
    checkpoint: () -> Unit = {}
): JpegInspectionResult {
    return inspectJpeg(
        input = input,
        expectedDimensions = expectedDimensions,
        stopAfterFirstScanHeader = false,
        allowTrailingBytes = allowTrailingBytes,
        checkpoint = checkpoint
    )
}

internal fun File.inspectCompleteJpeg(
    expectedDimensions: JpegDimensions? = null,
    allowTrailingBytes: Boolean = false,
    checkpoint: () -> Unit = {}
): JpegInspectionResult {
    if (!isFile || length() <= 0L) {
        return JpegInspectionResult.Invalid("JPEG file is missing or empty.")
    }
    return inputStream().use { input ->
        inspectCompleteJpeg(input, expectedDimensions, allowTrailingBytes, checkpoint)
    }
}

private fun inspectJpeg(
    input: InputStream,
    expectedDimensions: JpegDimensions?,
    stopAfterFirstScanHeader: Boolean,
    allowTrailingBytes: Boolean,
    checkpoint: () -> Unit
): JpegInspectionResult {
    val reader = BufferedJpegReader(input, checkpoint)
    fun invalid(message: String): JpegInspectionResult.Invalid {
        return JpegInspectionResult.Invalid("$message (byte ${reader.bytesRead})")
    }

    if (reader.readByte() != JPEG_MARKER_PREFIX || reader.readByte() != JPEG_SOI_CODE) {
        return invalid("JPEG does not start with an SOI marker.")
    }

    var frame: FrameInfo? = null
    var scanCount = 0
    var entropyBytes = 0L
    var pendingMarkerCode: Int? = null

    while (true) {
        if (scanCount == 0 && reader.bytesRead > MAX_JPEG_HEADER_BYTES) {
            return invalid("JPEG header exceeds the safe inspection limit.")
        }
        val markerCode = pendingMarkerCode ?: when (
            val marker = reader.readMarkerCode(
                maximumBytes = MAX_JPEG_HEADER_BYTES.takeIf { scanCount == 0 }
            )
        ) {
            is MarkerRead.Valid -> marker.code
            is MarkerRead.Invalid -> return invalid(marker.reason)
        }
        pendingMarkerCode = null

        when {
            markerCode == JPEG_SOI_CODE -> return invalid("JPEG contains a repeated SOI marker.")
            markerCode == JPEG_EOI_CODE -> {
                val resolvedFrame = frame ?: return invalid("JPEG ended before a frame header.")
                if (scanCount == 0) return invalid("JPEG ended before a scan.")
                if (entropyBytes == 0L) return invalid("JPEG contains no entropy-coded image data.")
                if (!allowTrailingBytes && reader.readByte() >= 0) {
                    return invalid("JPEG contains bytes after the EOI marker.")
                }
                return validStructure(resolvedFrame, scanCount, entropyBytes, reader.bytesRead)
            }

            markerCode in JPEG_RESTART_CODE_RANGE -> {
                return invalid("JPEG contains a restart marker outside entropy-coded data.")
            }

            markerCode == JPEG_TEMPORARY_CODE -> Unit
            markerCode == JPEG_SOS_CODE -> {
                val resolvedFrame = frame ?: return invalid("JPEG scan appears before its frame header.")
                val scanError = consumeScanHeader(reader, resolvedFrame.componentCount)
                if (scanError != null) return invalid(scanError)
                if (scanCount == 0 && reader.bytesRead > MAX_JPEG_HEADER_BYTES) {
                    return invalid("JPEG header exceeds the safe inspection limit.")
                }
                scanCount++

                if (stopAfterFirstScanHeader) {
                    return validStructure(resolvedFrame, scanCount, entropyBytes, reader.bytesRead)
                }

                val scan = reader.readEntropyData()
                if (scan is EntropyRead.Invalid) return invalid(scan.reason)
                scan as EntropyRead.Valid
                if (scan.dataBytes == 0L) {
                    return invalid("JPEG scan contains no entropy-coded image data.")
                }
                entropyBytes += scan.dataBytes
                pendingMarkerCode = scan.nextMarkerCode
            }

            markerCode.isStartOfFrameCode() -> {
                if (frame != null) return invalid("JPEG contains more than one frame header.")
                val parsedFrame = consumeFrameHeader(reader, markerCode)
                if (parsedFrame is FrameRead.Invalid) return invalid(parsedFrame.reason)
                parsedFrame as FrameRead.Valid
                if (expectedDimensions != null && parsedFrame.frame.dimensions != expectedDimensions) {
                    return invalid(
                        "JPEG dimensions ${parsedFrame.frame.dimensions.width} x " +
                            "${parsedFrame.frame.dimensions.height} do not match the expected " +
                            "${expectedDimensions.width} x ${expectedDimensions.height}."
                    )
                }
                frame = parsedFrame.frame
            }

            else -> {
                val segmentLength = reader.readUnsignedShort()
                    ?: return invalid("JPEG segment length is truncated.")
                if (segmentLength < 2) return invalid("JPEG segment has an invalid length.")
                if (!reader.skipExact(segmentLength - 2)) {
                    return invalid("JPEG segment payload is truncated.")
                }
            }
        }
    }
}

private fun validStructure(
    frame: FrameInfo,
    scanCount: Int,
    entropyBytes: Long,
    totalBytes: Long
): JpegInspectionResult.Valid {
    return JpegInspectionResult.Valid(
        JpegStructure(
            dimensions = frame.dimensions,
            frameMarker = JPEG_MARKER_PREFIX shl 8 or frame.markerCode,
            precision = frame.precision,
            componentCount = frame.componentCount,
            scanCount = scanCount,
            entropyBytes = entropyBytes,
            totalBytes = totalBytes
        )
    )
}

private fun consumeScanHeader(reader: BufferedJpegReader, frameComponentCount: Int): String? {
    val length = reader.readUnsignedShort() ?: return "JPEG scan length is truncated."
    if (length < MINIMUM_SOS_LENGTH) return "JPEG scan has an invalid length."

    val scanComponentCount = reader.readByte()
    if (scanComponentCount !in 1..frameComponentCount) {
        return "JPEG scan has an invalid component count."
    }
    val expectedLength = 6 + 2 * scanComponentCount
    if (length != expectedLength) return "JPEG scan length does not match its component count."
    if (!reader.skipExact(length - 3)) return "JPEG scan header is truncated."
    return null
}

private fun consumeFrameHeader(reader: BufferedJpegReader, markerCode: Int): FrameRead {
    val length = reader.readUnsignedShort()
        ?: return FrameRead.Invalid("JPEG frame length is truncated.")
    if (length < MINIMUM_SOF_LENGTH) return FrameRead.Invalid("JPEG frame has an invalid length.")

    val precision = reader.readByte()
    val height = reader.readUnsignedShort()
    val width = reader.readUnsignedShort()
    val componentCount = reader.readByte()
    if (precision <= 0 || height == null || width == null || componentCount <= 0) {
        return FrameRead.Invalid("JPEG frame header is truncated.")
    }
    if (width <= 0 || height <= 0) {
        return FrameRead.Invalid("JPEG frame has invalid dimensions.")
    }
    val expectedLength = 8 + 3 * componentCount
    if (length != expectedLength) {
        return FrameRead.Invalid("JPEG frame length does not match its component count.")
    }
    if (!reader.skipExact(3 * componentCount)) {
        return FrameRead.Invalid("JPEG frame component data is truncated.")
    }

    return FrameRead.Valid(
        FrameInfo(
            dimensions = JpegDimensions(width = width, height = height),
            markerCode = markerCode,
            precision = precision,
            componentCount = componentCount
        )
    )
}

private class BufferedJpegReader(
    private val input: InputStream,
    private val checkpoint: () -> Unit
) {
    private val buffer = ByteArray(STREAM_BUFFER_SIZE)
    private var position = 0
    private var limit = 0

    var bytesRead: Long = 0L
        private set

    fun readByte(): Int {
        if (position >= limit && !refill()) return -1
        bytesRead++
        return buffer[position++].toInt() and 0xFF
    }

    fun readUnsignedShort(): Int? {
        val high = readByte()
        val low = readByte()
        if (high < 0 || low < 0) return null
        return high shl 8 or low
    }

    fun skipExact(byteCount: Int): Boolean {
        var remaining = byteCount
        while (remaining > 0) {
            if (position >= limit && !refill()) return false
            val skipped = minOf(remaining, limit - position)
            position += skipped
            bytesRead += skipped.toLong()
            remaining -= skipped
        }
        return true
    }

    fun readMarkerCode(maximumBytes: Long?): MarkerRead {
        val prefix = readByte()
        if (prefix < 0) return MarkerRead.Invalid("JPEG ended before an EOI marker.")
        if (prefix != JPEG_MARKER_PREFIX) {
            return MarkerRead.Invalid("JPEG marker prefix is missing outside entropy-coded data.")
        }

        var code = readByte()
        while (code == JPEG_MARKER_PREFIX) {
            checkpoint()
            if (maximumBytes != null && bytesRead > maximumBytes) {
                return MarkerRead.Invalid("JPEG header exceeds the safe inspection limit.")
            }
            code = readByte()
        }
        if (code < 0) return MarkerRead.Invalid("JPEG marker is truncated.")
        if (code == 0) return MarkerRead.Invalid("JPEG contains a stuffed byte outside entropy-coded data.")
        return MarkerRead.Valid(code)
    }

    fun readEntropyData(): EntropyRead {
        var dataBytes = 0L
        while (true) {
            val value = readByte()
            if (value < 0) return EntropyRead.Invalid("JPEG entropy data is truncated before EOI.")
            if (value != JPEG_MARKER_PREFIX) {
                dataBytes++
                continue
            }

            var code = readByte()
            while (code == JPEG_MARKER_PREFIX) {
                code = readByte()
            }
            if (code < 0) return EntropyRead.Invalid("JPEG entropy marker is truncated.")
            when {
                code == 0 -> dataBytes++
                code in JPEG_RESTART_CODE_RANGE -> Unit
                else -> return EntropyRead.Valid(nextMarkerCode = code, dataBytes = dataBytes)
            }
        }
    }

    private fun refill(): Boolean {
        while (true) {
            checkpoint()
            val readCount = input.read(buffer)
            if (readCount < 0) return false
            if (readCount == 0) {
                val singleByte = input.read()
                if (singleByte < 0) return false
                buffer[0] = singleByte.toByte()
                position = 0
                limit = 1
                return true
            }
            position = 0
            limit = readCount
            return true
        }
    }
}

private data class FrameInfo(
    val dimensions: JpegDimensions,
    val markerCode: Int,
    val precision: Int,
    val componentCount: Int
)

private sealed interface FrameRead {
    data class Valid(val frame: FrameInfo) : FrameRead

    data class Invalid(val reason: String) : FrameRead
}

private sealed interface MarkerRead {
    data class Valid(val code: Int) : MarkerRead

    data class Invalid(val reason: String) : MarkerRead
}

private sealed interface EntropyRead {
    data class Valid(val nextMarkerCode: Int, val dataBytes: Long) : EntropyRead

    data class Invalid(val reason: String) : EntropyRead
}

private fun Int.isStartOfFrameCode(): Boolean {
    return this in 0xC0..0xC3 ||
        this in 0xC5..0xC7 ||
        this in 0xC9..0xCB ||
        this in 0xCD..0xCF
}

private const val JPEG_MARKER_PREFIX = 0xFF
private const val JPEG_SOI_CODE = 0xD8
private const val JPEG_EOI_CODE = 0xD9
private const val JPEG_SOS_CODE = 0xDA
private const val JPEG_TEMPORARY_CODE = 0x01
private val JPEG_RESTART_CODE_RANGE = 0xD0..0xD7
private const val MINIMUM_SOF_LENGTH = 8
private const val MINIMUM_SOS_LENGTH = 8
private const val STREAM_BUFFER_SIZE = 16_384
