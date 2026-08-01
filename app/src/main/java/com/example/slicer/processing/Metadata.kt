package com.example.slicer.processing

import com.example.slicer.core.*

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ParsedJpegMetadata(
    val nonMetadataSegments: List<ByteArray>,
    val metadataSegments: List<ByteArray>,
    val scanStartIndex: Int
)

internal suspend fun Context.preserveJpegMetadata(
    sourceUri: Uri,
    destinationUri: Uri
): Boolean {
    currentCoroutineContext().ensureActive()
    val sourceBytes = contentResolver.openInputStream(sourceUri)?.use { it.readBytes() } ?: return false
    currentCoroutineContext().ensureActive()
    val destinationBytes = contentResolver.openInputStream(destinationUri)?.use { it.readBytes() } ?: return false

    val sourceParsed = parseJpegMetadataBlocks(sourceBytes) ?: return false
    if (sourceParsed.metadataSegments.isEmpty()) {
        return true
    }

    val destinationBounds = android.graphics.BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    android.graphics.BitmapFactory.decodeByteArray(destinationBytes, 0, destinationBytes.size, destinationBounds)
    if (destinationBounds.outWidth <= 0 || destinationBounds.outHeight <= 0) {
        return false
    }

    val destinationBitmap = android.graphics.BitmapFactory.decodeByteArray(destinationBytes, 0, destinationBytes.size)
    if (destinationBitmap == null ||
        destinationBitmap.width != destinationBounds.outWidth ||
        destinationBitmap.height != destinationBounds.outHeight
    ) {
        destinationBitmap?.recycle()
        return false
    }

    return try {
        val mergedBytes = mergeJpegMetadataBytes(sourceParsed, destinationBytes)
        if (mergedBytes == null) {
            return false
        }

        if (!mergedBytes.isLikelyUsableJpeg(destinationBitmap.width, destinationBitmap.height, destinationBitmap)) {
            Log.w(
                LOG_TAG,
                "Metadata merge validation failed; preserving source metadata would change decoded JPEG content."
            )
            return false
        }

        try {
            currentCoroutineContext().ensureActive()
            contentResolver.openOutputStream(destinationUri, "wt")?.use { output ->
                output.write(mergedBytes)
                output.flush()
            } ?: throw java.io.IOException("Could not open destination for metadata copy.")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    } finally {
        destinationBitmap.recycle()
    }
}

internal fun mergeJpegMetadataBytes(
    sourceBytes: ByteArray,
    destinationBytes: ByteArray
): ByteArray? {
    val sourceParsed = parseJpegMetadataBlocks(sourceBytes) ?: return null
    return mergeJpegMetadataBytes(sourceParsed, destinationBytes)
}

private fun mergeJpegMetadataBytes(
    sourceParsed: ParsedJpegMetadata,
    destinationBytes: ByteArray
): ByteArray? {
    val destinationParsed = parseJpegMetadataBlocks(destinationBytes) ?: return null
    return buildJpegWithSourceMetadata(destinationParsed, sourceParsed, destinationBytes)
}

internal fun parseJpegMetadataBlocks(bytes: ByteArray): ParsedJpegMetadata? {
    if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) {
        return null
    }

    val nonMetadataSegments = mutableListOf<ByteArray>()
    val metadataSegments = mutableListOf<ByteArray>()

    var index = 2
    while (index < bytes.size - 1) {
        if (bytes[index] != 0xFF.toByte()) {
            return null
        }

        val marker = readBigEndianUnsignedShort(bytes, index)
        if (marker == JPEG_END_OF_IMAGE) {
            return ParsedJpegMetadata(
                nonMetadataSegments = nonMetadataSegments,
                metadataSegments = metadataSegments,
                scanStartIndex = index + 2
            )
        }

        if (marker == JPEG_START_OF_IMAGE) {
            index += 2
            continue
        }

        if (marker == JPEG_START_OF_SCAN) {
            val remaining = bytes.size - index
            if (remaining < 6) {
                return null
            }

            val length = readBigEndianUnsignedShort(bytes, index + 2)
            if (length < 2) {
                return null
            }

            if (index + 2 + length > bytes.size) {
                return null
            }

            return ParsedJpegMetadata(
                nonMetadataSegments = nonMetadataSegments,
                metadataSegments = metadataSegments,
                scanStartIndex = index
            )
        }

        if (isStandaloneMarker(marker)) {
            nonMetadataSegments.add(bytes.copyOfRange(index, index + 2))
            index += 2
            continue
        }

        if (index + 4 > bytes.size) {
            return null
        }

        val length = readBigEndianUnsignedShort(bytes, index + 2)
        if (length < 2) {
            return null
        }

        val totalSegmentLength = 2 + length
        val segmentEnd = index + totalSegmentLength
        if (segmentEnd > bytes.size) {
            return null
        }

        val segment = bytes.copyOfRange(index, segmentEnd)
        if (isPreservableMetadataMarker(marker)) {
            metadataSegments.add(segment)
        } else {
            nonMetadataSegments.add(segment)
        }

        index = segmentEnd
    }

    return null
}

private fun isStandaloneMarker(marker: Int): Boolean {
    return (marker in 0xFFD0..0xFFD7) || marker == JPEG_END_OF_IMAGE || marker == 0xFF01
}

private fun isPreservableMetadataMarker(marker: Int): Boolean {
    return marker == JPEG_SEGMENT_COMMENT ||
        (marker in JPEG_SEGMENT_APP0..JPEG_SEGMENT_APPF)
}

private fun buildJpegWithSourceMetadata(
    destinationParsed: ParsedJpegMetadata,
    sourceParsed: ParsedJpegMetadata,
    destinationBytes: ByteArray
): ByteArray? {
    if (destinationParsed.scanStartIndex <= 0 || destinationParsed.scanStartIndex > destinationBytes.size) {
        return null
    }

    val output = java.io.ByteArrayOutputStream(destinationBytes.size + sourceParsed.metadataSegments.sumOf { it.size })
    output.write(0xFF)
    output.write(0xD8)

    for (segment in sourceParsed.metadataSegments) {
        output.write(segment)
    }
    for (segment in destinationParsed.nonMetadataSegments) {
        output.write(segment)
    }
    output.write(destinationBytes, destinationParsed.scanStartIndex, destinationBytes.size - destinationParsed.scanStartIndex)

    return output.toByteArray()
}

private fun readBigEndianUnsignedShort(bytes: ByteArray, startIndex: Int): Int {
    return ((bytes[startIndex].toInt() and 0xFF) shl 8) or (bytes[startIndex + 1].toInt() and 0xFF)
}
