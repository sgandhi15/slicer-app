package com.example.slicer.processing

import com.example.slicer.core.DEFAULT_COPY_BUFFER_BYTES

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal sealed interface MetadataRewriteResult {
    data object NoMetadata : MetadataRewriteResult

    data class Rewritten(
        val metadataSegmentCount: Int,
        val metadataBytes: Long
    ) : MetadataRewriteResult

    data class Invalid(val reason: String) : MetadataRewriteResult
}

internal fun rewriteJpegWithSourceMetadata(
    source: InputStream,
    encoded: InputStream,
    output: OutputStream,
    expectedDimensions: JpegDimensions,
    checkpoint: () -> Unit = {}
): MetadataRewriteResult {
    return try {
        val sourceHeader = copySourceMetadataHeader(
            source = LimitedHeaderInputStream(source, MAX_JPEG_HEADER_BYTES),
            output = output,
            expectedDimensions = expectedDimensions,
            checkpoint = checkpoint
        )
        if (sourceHeader.metadataSegmentCount == 0) {
            MetadataRewriteResult.NoMetadata
        } else {
            copyEncodedJpeg(
                encoded = encoded,
                output = output,
                replacedMetadataMarkers = sourceHeader.metadataMarkers,
                checkpoint = checkpoint
            )
            MetadataRewriteResult.Rewritten(
                metadataSegmentCount = sourceHeader.metadataSegmentCount,
                metadataBytes = sourceHeader.metadataBytes
            )
        }
    } catch (invalid: InvalidJpegHeaderException) {
        MetadataRewriteResult.Invalid(invalid.message ?: "JPEG header is invalid.")
    }
}

private fun copySourceMetadataHeader(
    source: InputStream,
    output: OutputStream,
    expectedDimensions: JpegDimensions,
    checkpoint: () -> Unit
): SourceMetadataHeader {
    requireStartOfImage(source, "Source")
    val metadata = SourceMetadataPolicy()
    var frameDimensions: JpegDimensions? = null

    while (true) {
        checkpoint()
        val markerCode = readMarkerCode(source, "Source", checkpoint)
        when {
            markerCode == JPEG_SOI_CODE -> invalidHeader("Source JPEG contains a repeated SOI marker.")
            markerCode == JPEG_EOI_CODE -> invalidHeader("Source JPEG ended before its first scan.")
            markerCode in JPEG_RESTART_CODE_RANGE -> {
                invalidHeader("Source JPEG contains a restart marker outside entropy-coded data.")
            }

            markerCode == JPEG_TEMPORARY_CODE -> Unit
            markerCode == JPEG_SOS_CODE -> {
                consumeScanHeader(source, frameDimensions != null, "Source", checkpoint)
                val dimensions = frameDimensions
                    ?: invalidHeader("Source JPEG scan appears before its frame header.")
                if (dimensions != expectedDimensions) {
                    invalidHeader(
                        "Source JPEG dimensions ${dimensions.width} x ${dimensions.height} changed from " +
                            "${expectedDimensions.width} x ${expectedDimensions.height}."
                    )
                }
                return metadata.finish()
            }

            markerCode.isStartOfFrameCode() -> {
                if (frameDimensions != null) invalidHeader("Source JPEG contains more than one frame header.")
                frameDimensions = consumeFrameDimensions(source, "Source", checkpoint)
            }

            else -> {
                val segmentLength = readSegmentLength(source, "Source")
                val payloadBytes = segmentLength - 2
                if (markerCode.isApplicationOrCommentCode()) {
                    copyRecognizedMetadataSegment(
                        source = source,
                        output = output,
                        markerCode = markerCode,
                        segmentLength = segmentLength,
                        metadata = metadata,
                        checkpoint = checkpoint
                    )
                } else {
                    skipExactly(source, payloadBytes, "Source JPEG segment", checkpoint)
                }
            }
        }
    }
}

private fun copyEncodedJpeg(
    encoded: InputStream,
    output: OutputStream,
    replacedMetadataMarkers: BooleanArray,
    checkpoint: () -> Unit
) {
    requireStartOfImage(encoded, "Encoded")

    while (true) {
        checkpoint()
        val markerCode = readMarkerCode(encoded, "Encoded", checkpoint)
        when {
            markerCode == JPEG_SOI_CODE -> invalidHeader("Encoded JPEG contains a repeated SOI marker.")
            markerCode == JPEG_EOI_CODE -> invalidHeader("Encoded JPEG ended before its first scan.")
            markerCode in JPEG_RESTART_CODE_RANGE -> {
                invalidHeader("Encoded JPEG contains a restart marker outside entropy-coded data.")
            }

            markerCode == JPEG_TEMPORARY_CODE -> writeMarker(output, markerCode)
            else -> {
                val segmentLength = readSegmentLength(encoded, "Encoded")
                val payloadBytes = segmentLength - 2
                val replaceSegment = replacedMetadataMarkers[markerCode]
                if (replaceSegment) {
                    skipExactly(encoded, payloadBytes, "Encoded metadata", checkpoint)
                } else {
                    writeMarker(output, markerCode)
                    writeUnsignedShort(output, segmentLength)
                    copyExactly(encoded, output, payloadBytes, "Encoded JPEG segment", checkpoint)
                }

                if (markerCode == JPEG_SOS_CODE) {
                    copyRemaining(encoded, output, checkpoint)
                    return
                }
            }
        }
    }
}

private fun requireStartOfImage(input: InputStream, label: String) {
    if (readByte(input) != JPEG_MARKER_PREFIX || readByte(input) != JPEG_SOI_CODE) {
        invalidHeader("$label JPEG does not start with an SOI marker.")
    }
}

private fun readMarkerCode(input: InputStream, label: String, checkpoint: () -> Unit): Int {
    if (readByte(input) != JPEG_MARKER_PREFIX) {
        invalidHeader("$label JPEG marker prefix is missing.")
    }
    var code = readByte(input)
    while (code == JPEG_MARKER_PREFIX) {
        checkpoint()
        code = readByte(input)
    }
    if (code < 0) invalidHeader("$label JPEG marker is truncated.")
    if (code == 0) invalidHeader("$label JPEG contains a stuffed byte outside entropy-coded data.")
    return code
}

private fun readSegmentLength(input: InputStream, label: String): Int {
    val length = readUnsignedShort(input)
    if (length == null) invalidHeader("$label JPEG segment length is truncated.")
    if (length < 2) invalidHeader("$label JPEG segment has an invalid length.")
    return length
}

private fun consumeFrameDimensions(
    input: InputStream,
    label: String,
    checkpoint: () -> Unit
): JpegDimensions {
    val length = readSegmentLength(input, label)
    if (length < MINIMUM_SOF_LENGTH) invalidHeader("$label JPEG frame has an invalid length.")
    val precision = readByte(input)
    val height = readUnsignedShort(input)
    val width = readUnsignedShort(input)
    val componentCount = readByte(input)
    if (precision <= 0 || height == null || width == null || componentCount <= 0) {
        invalidHeader("$label JPEG frame header is invalid or truncated.")
    }
    if (width <= 0 || height <= 0) invalidHeader("$label JPEG frame has invalid dimensions.")
    if (length != 8 + 3 * componentCount) {
        invalidHeader("$label JPEG frame length does not match its component count.")
    }
    skipExactly(input, 3 * componentCount, "$label JPEG frame", checkpoint)
    return JpegDimensions(width = width, height = height)
}

private fun consumeScanHeader(
    input: InputStream,
    hasFrame: Boolean,
    label: String,
    checkpoint: () -> Unit
) {
    if (!hasFrame) invalidHeader("$label JPEG scan appears before its frame header.")
    val length = readSegmentLength(input, label)
    if (length < MINIMUM_SOS_LENGTH) invalidHeader("$label JPEG scan has an invalid length.")
    val componentCount = readByte(input)
    if (componentCount <= 0 || length != 6 + 2 * componentCount) {
        invalidHeader("$label JPEG scan length does not match its component count.")
    }
    skipExactly(input, length - 3, "$label JPEG scan", checkpoint)
}

private fun copyRecognizedMetadataSegment(
    source: InputStream,
    output: OutputStream,
    markerCode: Int,
    segmentLength: Int,
    metadata: SourceMetadataPolicy,
    checkpoint: () -> Unit
) {
    val payloadBytes = segmentLength - 2
    val prefix = readExactly(
        input = source,
        byteCount = minOf(payloadBytes, METADATA_PREFIX_BYTES),
        label = "Source metadata prefix",
        checkpoint = checkpoint
    )
    val shouldPreserve = metadata.shouldPreserve(markerCode, prefix, payloadBytes)
    val remainingBytes = payloadBytes - prefix.size

    if (shouldPreserve) {
        if (metadata.metadataSegmentCount == 0) writeMarker(output, JPEG_SOI_CODE)
        writeMarker(output, markerCode)
        writeUnsignedShort(output, segmentLength)
        output.write(prefix)
        copyExactly(source, output, remainingBytes, "Source metadata", checkpoint)
        metadata.record(markerCode, segmentLength.toLong() + 2L)
    } else {
        skipExactly(source, remainingBytes, "Unsupported source metadata", checkpoint)
    }
}

private fun readExactly(
    input: InputStream,
    byteCount: Int,
    label: String,
    checkpoint: () -> Unit
): ByteArray {
    val bytes = ByteArray(byteCount)
    var offset = 0
    while (offset < bytes.size) {
        checkpoint()
        val readCount = input.read(bytes, offset, bytes.size - offset)
        when {
            readCount < 0 -> invalidHeader("$label is truncated.")
            readCount == 0 -> {
                val value = input.read()
                if (value < 0) invalidHeader("$label is truncated.")
                bytes[offset++] = value.toByte()
            }

            else -> offset += readCount
        }
    }
    return bytes
}

private fun copyExactly(
    input: InputStream,
    output: OutputStream,
    byteCount: Int,
    label: String,
    checkpoint: () -> Unit
) {
    val buffer = ByteArray(minOf(DEFAULT_COPY_BUFFER_BYTES, byteCount.coerceAtLeast(1)))
    var remaining = byteCount
    while (remaining > 0) {
        checkpoint()
        val readCount = input.read(buffer, 0, minOf(buffer.size, remaining))
        when {
            readCount < 0 -> invalidHeader("$label is truncated.")
            readCount == 0 -> {
                val value = input.read()
                if (value < 0) invalidHeader("$label is truncated.")
                output.write(value)
                remaining--
            }

            else -> {
                output.write(buffer, 0, readCount)
                remaining -= readCount
            }
        }
    }
}

private fun skipExactly(
    input: InputStream,
    byteCount: Int,
    label: String,
    checkpoint: () -> Unit
) {
    val buffer = ByteArray(minOf(DEFAULT_COPY_BUFFER_BYTES, byteCount.coerceAtLeast(1)))
    var remaining = byteCount
    while (remaining > 0) {
        checkpoint()
        val readCount = input.read(buffer, 0, minOf(buffer.size, remaining))
        when {
            readCount < 0 -> invalidHeader("$label is truncated.")
            readCount == 0 -> {
                if (input.read() < 0) invalidHeader("$label is truncated.")
                remaining--
            }

            else -> remaining -= readCount
        }
    }
}

private fun copyRemaining(input: InputStream, output: OutputStream, checkpoint: () -> Unit) {
    val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
    while (true) {
        checkpoint()
        val readCount = input.read(buffer)
        when {
            readCount < 0 -> return
            readCount == 0 -> {
                val value = input.read()
                if (value < 0) return
                output.write(value)
            }

            else -> output.write(buffer, 0, readCount)
        }
    }
}

private fun readByte(input: InputStream): Int = input.read()

private fun readUnsignedShort(input: InputStream): Int? {
    val high = readByte(input)
    val low = readByte(input)
    if (high < 0 || low < 0) return null
    return high shl 8 or low
}

private fun writeMarker(output: OutputStream, markerCode: Int) {
    output.write(JPEG_MARKER_PREFIX)
    output.write(markerCode)
}

private fun writeUnsignedShort(output: OutputStream, value: Int) {
    output.write(value ushr 8 and 0xFF)
    output.write(value and 0xFF)
}

private fun Int.isApplicationOrCommentCode(): Boolean {
    return this == JPEG_COMMENT_CODE || this in JPEG_APP0_CODE..JPEG_APP15_CODE
}

private fun Int.isStartOfFrameCode(): Boolean {
    return this in 0xC0..0xC3 ||
        this in 0xC5..0xC7 ||
        this in 0xC9..0xCB ||
        this in 0xCD..0xCF
}

private fun invalidHeader(message: String): Nothing = throw InvalidJpegHeaderException(message)

private data class SourceMetadataHeader(
    val metadataMarkers: BooleanArray,
    val metadataSegmentCount: Int,
    val metadataBytes: Long
)

private class SourceMetadataPolicy {
    val metadataMarkers = BooleanArray(256)
    var metadataSegmentCount: Int = 0
        private set
    var metadataBytes: Long = 0L
        private set

    private var expectedIccChunks = 0
    private var nextIccChunk = 1
    private var expectedIccProfileBytes = 0L
    private var iccProfileBytes = 0L

    fun shouldPreserve(markerCode: Int, prefix: ByteArray, payloadBytes: Int): Boolean {
        return when (markerCode) {
            JPEG_APP0_CODE -> validateJfif(prefix, payloadBytes)
            JPEG_APP1_CODE -> prefix.startsWith(EXIF_SIGNATURE)
            JPEG_APP2_CODE -> validateIccChunk(prefix, payloadBytes)
            JPEG_APP13_CODE -> prefix.startsWith(PHOTOSHOP_SIGNATURE)
            JPEG_COMMENT_CODE -> true
            else -> false
        }
    }

    fun record(markerCode: Int, segmentBytes: Long) {
        metadataMarkers[markerCode] = true
        metadataSegmentCount++
        metadataBytes = addWithoutOverflow(metadataBytes, segmentBytes)
    }

    fun finish(): SourceMetadataHeader {
        if (expectedIccChunks > 0 && nextIccChunk != expectedIccChunks + 1) {
            invalidHeader("Source JPEG contains an incomplete ICC profile.")
        }
        if (expectedIccChunks > 0 && iccProfileBytes != expectedIccProfileBytes) {
            invalidHeader("Source JPEG ICC profile size does not match its chunk data.")
        }
        if (metadataSegmentCount > 0) {
            // A retained encoder JFIF marker would otherwise appear after source EXIF/ICC data.
            metadataMarkers[JPEG_APP0_CODE] = true
        }
        return SourceMetadataHeader(
            metadataMarkers = metadataMarkers,
            metadataSegmentCount = metadataSegmentCount,
            metadataBytes = metadataBytes
        )
    }

    private fun validateJfif(prefix: ByteArray, payloadBytes: Int): Boolean {
        if (!prefix.startsWith(JFIF_SIGNATURE)) return false
        if (prefix.size < JFIF_HEADER_BYTES) invalidHeader("Source JPEG contains a truncated JFIF segment.")
        val densityUnits = prefix[7].toInt() and 0xFF
        val xDensity = prefix.readUnsignedShortAt(8)
        val yDensity = prefix.readUnsignedShortAt(10)
        val thumbnailWidth = prefix[12].toInt() and 0xFF
        val thumbnailHeight = prefix[13].toInt() and 0xFF
        val expectedPayloadBytes = JFIF_HEADER_BYTES + 3 * thumbnailWidth * thumbnailHeight
        if (densityUnits !in 0..2 || xDensity <= 0 || yDensity <= 0 || payloadBytes != expectedPayloadBytes) {
            invalidHeader("Source JPEG contains an invalid JFIF segment.")
        }
        return true
    }

    private fun validateIccChunk(prefix: ByteArray, payloadBytes: Int): Boolean {
        if (!prefix.startsWith(ICC_SIGNATURE)) return false
        if (prefix.size < ICC_CHUNK_HEADER_BYTES) {
            invalidHeader("Source JPEG contains a truncated ICC profile chunk.")
        }

        val sequence = prefix[ICC_SEQUENCE_OFFSET].toInt() and 0xFF
        val chunkCount = prefix[ICC_COUNT_OFFSET].toInt() and 0xFF
        if (sequence != nextIccChunk || chunkCount <= 0) {
            invalidHeader("Source JPEG contains invalid ICC profile chunk ordering.")
        }
        if (sequence == 1) {
            expectedIccChunks = chunkCount
            if (prefix.size < ICC_PROFILE_HEADER_END_OFFSET) {
                invalidHeader("Source JPEG contains a truncated ICC profile header.")
            }
            if (!prefix.matchesAt(ICC_COLOR_SPACE_OFFSET, RGB_COLOR_SPACE)) {
                invalidHeader("Source JPEG ICC profile is not compatible with RGB output.")
            }
            if (!prefix.matchesAt(ICC_SIGNATURE_OFFSET, ICC_HEADER_SIGNATURE)) {
                invalidHeader("Source JPEG ICC profile header is invalid.")
            }
            expectedIccProfileBytes = prefix.readUnsignedIntAt(ICC_DECLARED_SIZE_OFFSET)
            if (expectedIccProfileBytes < ICC_PROFILE_HEADER_BYTES ||
                expectedIccProfileBytes > MAX_JPEG_HEADER_BYTES
            ) {
                invalidHeader("Source JPEG ICC profile declares an invalid size.")
            }
        } else if (chunkCount != expectedIccChunks) {
            invalidHeader("Source JPEG ICC profile chunk counts do not match.")
        }
        iccProfileBytes = addWithoutOverflow(iccProfileBytes, (payloadBytes - ICC_CHUNK_HEADER_BYTES).toLong())
        nextIccChunk++
        return true
    }
}

private class LimitedHeaderInputStream(
    private val delegate: InputStream,
    private val maximumBytes: Long
) : InputStream() {
    private var bytesRead = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) recordRead(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val readCount = delegate.read(buffer, offset, length)
        if (readCount > 0) recordRead(readCount)
        return readCount
    }

    private fun recordRead(byteCount: Int) {
        if (bytesRead > maximumBytes - byteCount.toLong()) {
            invalidHeader("JPEG metadata header exceeds the safe streaming limit.")
        }
        bytesRead += byteCount.toLong()
    }
}

private class InvalidJpegHeaderException(message: String) : IOException(message)

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
}

private fun ByteArray.matchesAt(offset: Int, expected: ByteArray): Boolean {
    return offset >= 0 && size - offset >= expected.size &&
        expected.indices.all { index -> this[offset + index] == expected[index] }
}

private fun ByteArray.readUnsignedShortAt(offset: Int): Int {
    return (this[offset].toInt() and 0xFF) shl 8 or (this[offset + 1].toInt() and 0xFF)
}

private fun ByteArray.readUnsignedIntAt(offset: Int): Long {
    return (this[offset].toLong() and 0xFFL) shl 24 or
        ((this[offset + 1].toLong() and 0xFFL) shl 16) or
        ((this[offset + 2].toLong() and 0xFFL) shl 8) or
        (this[offset + 3].toLong() and 0xFFL)
}

private fun addWithoutOverflow(left: Long, right: Long): Long {
    return if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

private const val JPEG_MARKER_PREFIX = 0xFF
private const val JPEG_SOI_CODE = 0xD8
private const val JPEG_EOI_CODE = 0xD9
private const val JPEG_SOS_CODE = 0xDA
private const val JPEG_TEMPORARY_CODE = 0x01
private const val JPEG_APP0_CODE = 0xE0
private const val JPEG_APP1_CODE = 0xE1
private const val JPEG_APP2_CODE = 0xE2
private const val JPEG_APP13_CODE = 0xED
private const val JPEG_APP15_CODE = 0xEF
private const val JPEG_COMMENT_CODE = 0xFE
private val JPEG_RESTART_CODE_RANGE = 0xD0..0xD7
private const val MINIMUM_SOF_LENGTH = 8
private const val MINIMUM_SOS_LENGTH = 8
private const val METADATA_PREFIX_BYTES = 142
private const val JFIF_HEADER_BYTES = 14
private const val ICC_SEQUENCE_OFFSET = 12
private const val ICC_COUNT_OFFSET = 13
private const val ICC_DECLARED_SIZE_OFFSET = 14
private const val ICC_COLOR_SPACE_OFFSET = 30
private const val ICC_SIGNATURE_OFFSET = 50
private const val ICC_CHUNK_HEADER_BYTES = 14
private const val ICC_PROFILE_HEADER_BYTES = 128L
private const val ICC_PROFILE_HEADER_END_OFFSET = ICC_CHUNK_HEADER_BYTES + 128
private val JFIF_SIGNATURE = byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0)
private val EXIF_SIGNATURE = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
private val ICC_SIGNATURE = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)
private val ICC_HEADER_SIGNATURE = "acsp".toByteArray(Charsets.US_ASCII)
private val PHOTOSHOP_SIGNATURE = "Photoshop 3.0\u0000".toByteArray(Charsets.US_ASCII)
private val RGB_COLOR_SPACE = "RGB ".toByteArray(Charsets.US_ASCII)
