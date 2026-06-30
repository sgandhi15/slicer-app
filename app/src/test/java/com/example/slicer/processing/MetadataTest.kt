package com.example.slicer.processing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataTest {
    @Test
    fun mergeJpegMetadataBytes_preservesSourceMetadataBeforeDestinationCodecSegments() {
        val sourceApp1 = segment(0xFFE1, byteArrayOf(0x45, 0x78, 0x69, 0x66))
        val sourceApp2 = segment(0xFFE2, byteArrayOf(0x49, 0x43, 0x43))
        val sourceApp13 = segment(0xFFED, byteArrayOf(0x49, 0x50, 0x54, 0x43))
        val sourceComment = segment(0xFFFE, byteArrayOf(0x43, 0x4F, 0x4D))
        val source = jpegOf(
            sourceApp1,
            sourceApp2,
            sourceApp13,
            sourceComment,
            segment(0xFFDB, byteArrayOf(0x01))
        )

        val destinationDqt = segment(0xFFDB, byteArrayOf(0x02))
        val destinationSof = segment(0xFFC0, byteArrayOf(0x03, 0x04))
        val destination = jpegOf(
            segment(0xFFE1, byteArrayOf(0x64, 0x73, 0x74)),
            destinationDqt,
            destinationSof
        )

        val merged = requireNotNull(mergeJpegMetadataBytes(source, destination))
        val segments = segmentsBeforeScan(merged)

        assertEquals(
            listOf(0xFFE1, 0xFFE2, 0xFFED, 0xFFFE, 0xFFDB, 0xFFC0),
            segments.map { it.marker }
        )
        assertArrayEquals(sourceApp1, segments[0].bytes)
        assertArrayEquals(sourceApp2, segments[1].bytes)
        assertArrayEquals(sourceApp13, segments[2].bytes)
        assertArrayEquals(sourceComment, segments[3].bytes)
        assertArrayEquals(destinationDqt, segments[4].bytes)
        assertArrayEquals(destinationSof, segments[5].bytes)
    }

    @Test
    fun mergeJpegMetadataBytes_replacesDestinationMetadata() {
        val source = jpegOf(segment(0xFFE1, byteArrayOf(0x01)))
        val destinationMetadata = segment(0xFFE1, byteArrayOf(0x02))
        val destination = jpegOf(destinationMetadata, segment(0xFFDB, byteArrayOf(0x03)))

        val merged = requireNotNull(mergeJpegMetadataBytes(source, destination))

        assertFalse(merged.containsSubarray(destinationMetadata))
        assertTrue(merged.containsSubarray(segment(0xFFE1, byteArrayOf(0x01))))
    }

    @Test
    fun parseJpegMetadataBlocks_splitsMetadataAndCodecSegments() {
        val parsed = parseJpegMetadataBlocks(
            jpegOf(
                segment(0xFFE0, byteArrayOf(0x01)),
                segment(0xFFDB, byteArrayOf(0x02)),
                segment(0xFFFE, byteArrayOf(0x03))
            )
        )

        assertNotNull(parsed)
        assertEquals(2, parsed!!.metadataSegments.size)
        assertEquals(1, parsed.nonMetadataSegments.size)
    }

    @Test
    fun mergeJpegMetadataBytes_rejectsInvalidJpegs() {
        val valid = jpegOf(segment(0xFFE1, byteArrayOf(0x01)))

        assertNull(mergeJpegMetadataBytes(byteArrayOf(0x00, 0x01), valid))
        assertNull(mergeJpegMetadataBytes(valid, byteArrayOf(0x00, 0x01)))
    }

    private data class TestSegment(
        val marker: Int,
        val bytes: ByteArray
    )

    private fun jpegOf(vararg segments: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        output.write(0xFF)
        output.write(0xD8)
        for (segment in segments) {
            output.write(segment)
        }
        output.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte()))
        return output.toByteArray()
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(
            ((marker ushr 8) and 0xFF).toByte(),
            (marker and 0xFF).toByte(),
            ((length ushr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte()
        ) + payload
    }

    private fun segmentsBeforeScan(bytes: ByteArray): List<TestSegment> {
        val segments = mutableListOf<TestSegment>()
        var index = 2
        while (index < bytes.size - 1) {
            val marker = ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
            if (marker == 0xFFDA) {
                break
            }

            val length = ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
            val end = index + 2 + length
            segments.add(TestSegment(marker, bytes.copyOfRange(index, end)))
            index = end
        }

        return segments
    }

    private fun ByteArray.containsSubarray(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false

        for (start in 0..(size - candidate.size)) {
            var matches = true
            for (offset in candidate.indices) {
                if (this[start + offset] != candidate[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }

        return false
    }
}
