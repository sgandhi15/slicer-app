package com.example.slicer.processing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MetadataTest {
    @Test
    fun streamsExifIccIptcAndCommentsInSourceOrder() {
        val sourceApp1 = jpegSegment(0xFFE1, exifPayload())
        val sourceApp2First = jpegSegment(0xFFE2, iccPayload(sequence = 1, chunkCount = 2))
        val sourceApp2Second = jpegSegment(0xFFE2, iccPayload(sequence = 2, chunkCount = 2))
        val sourceApp13 = jpegSegment(0xFFED, "Photoshop 3.0\u0000IPTC".toByteArray())
        val sourceComment = jpegSegment(0xFFFE, "comment".toByteArray())
        val source = testJpeg(
            headerSegments = listOf(
                sourceApp1,
                sourceApp2First,
                sourceApp2Second,
                sourceApp13,
                sourceComment
            )
        )
        val destinationApp0 = jpegSegment(0xFFE0, "JFIF".toByteArray())
        val replacedApp1 = jpegSegment(0xFFE1, "old".toByteArray())
        val destinationDqt = jpegSegment(0xFFDB, byteArrayOf(4, 5, 6))
        val destination = testJpeg(
            headerSegments = listOf(destinationApp0, replacedApp1, destinationDqt)
        )
        val output = ByteArrayOutputStream()

        val result = rewriteJpegWithSourceMetadata(
            source = ByteArrayInputStream(source),
            encoded = ByteArrayInputStream(destination),
            output = output,
            expectedDimensions = JpegDimensions(32, 24)
        )

        assertTrue(result is MetadataRewriteResult.Rewritten)
        assertEquals(5, (result as MetadataRewriteResult.Rewritten).metadataSegmentCount)
        val segments = segmentsBeforeScan(output.toByteArray())
        assertEquals(
            listOf(0xFFE1, 0xFFE2, 0xFFE2, 0xFFED, 0xFFFE, 0xFFDB, 0xFFC0),
            segments.map { it.marker }
        )
        assertArrayEquals(sourceApp1, segments[0].bytes)
        assertArrayEquals(sourceApp2First, segments[1].bytes)
        assertArrayEquals(sourceApp2Second, segments[2].bytes)
        assertArrayEquals(sourceApp13, segments[3].bytes)
        assertArrayEquals(sourceComment, segments[4].bytes)
        assertFalse(output.toByteArray().containsSubarray(replacedApp1))
        assertTrue(
            inspectCompleteJpeg(ByteArrayInputStream(output.toByteArray())) is JpegInspectionResult.Valid
        )
    }

    @Test
    fun progressiveScanTailRemainsByteForByteIdentical() {
        val source = testJpeg(
            headerSegments = listOf(jpegSegment(0xFFE1, exifPayload()))
        )
        val destination = testJpeg(
            frameMarker = JPEG_PROGRESSIVE_FRAME_MARKER,
            scans = listOf(
                JpegTestScan(
                    componentIds = intArrayOf(1),
                    entropy = byteArrayOf(0x11, 0xFF.toByte(), 0x00, 0x22),
                    followingSegments = listOf(jpegSegment(0xFFC4, byteArrayOf(0x01, 0x02)))
                ),
                JpegTestScan(
                    componentIds = intArrayOf(2, 3),
                    entropy = byteArrayOf(0x33, 0xFF.toByte(), 0xD0.toByte(), 0x44)
                )
            )
        )
        val output = ByteArrayOutputStream()

        val result = rewriteJpegWithSourceMetadata(
            source = ByteArrayInputStream(source),
            encoded = ByteArrayInputStream(destination),
            output = output,
            expectedDimensions = JpegDimensions(32, 24)
        )

        assertTrue(result is MetadataRewriteResult.Rewritten)
        val rewritten = output.toByteArray()
        val destinationScan = markerIndex(destination, 0xFFDA)
        val rewrittenScan = markerIndex(rewritten, 0xFFDA)
        assertArrayEquals(
            destination.copyOfRange(destinationScan, destination.size),
            rewritten.copyOfRange(rewrittenScan, rewritten.size)
        )
        val structure = inspectCompleteJpeg(ByteArrayInputStream(rewritten)) as JpegInspectionResult.Valid
        assertEquals(2, structure.structure.scanCount)
    }

    @Test
    fun sourceAdobeApp14IsNotTransplantedIntoEncodedColorData() {
        val sourceApp14 = jpegSegment(0xFFEE, "source-adobe".toByteArray())
        val destinationApp14 = jpegSegment(0xFFEE, "encoded-adobe".toByteArray())
        val source = testJpeg(
            headerSegments = listOf(
                jpegSegment(0xFFE1, exifPayload()),
                sourceApp14
            )
        )
        val destination = testJpeg(headerSegments = listOf(destinationApp14))
        val output = ByteArrayOutputStream()

        val result = rewriteJpegWithSourceMetadata(
            source = ByteArrayInputStream(source),
            encoded = ByteArrayInputStream(destination),
            output = output,
            expectedDimensions = JpegDimensions(32, 24)
        )

        assertTrue(result is MetadataRewriteResult.Rewritten)
        assertTrue(output.toByteArray().containsSubarray(destinationApp14))
        assertFalse(output.toByteArray().containsSubarray(sourceApp14))
    }

    @Test
    fun validJfifDensityIsPreservedButTruncatedJfifIsRejected() {
        val jfifPayload = byteArrayOf(
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
            1, 2, 0,
            0, 72,
            0, 72,
            0, 0
        )
        val jfif = jpegSegment(0xFFE0, jfifPayload)
        val output = ByteArrayOutputStream()

        val preserved = rewriteJpegWithSourceMetadata(
            source = ByteArrayInputStream(testJpeg(headerSegments = listOf(jfif))),
            encoded = ByteArrayInputStream(testJpeg()),
            output = output,
            expectedDimensions = JpegDimensions(32, 24)
        )
        val invalid = rewriteJpegWithSourceMetadata(
            source = ByteArrayInputStream(
                testJpeg(headerSegments = listOf(jpegSegment(0xFFE0, "JFIF\u0000".toByteArray())))
            ),
            encoded = ByteArrayInputStream(testJpeg()),
            output = ByteArrayOutputStream(),
            expectedDimensions = JpegDimensions(32, 24)
        )

        assertTrue(preserved is MetadataRewriteResult.Rewritten)
        assertTrue(output.toByteArray().containsSubarray(jfif))
        assertTrue(invalid is MetadataRewriteResult.Invalid)
    }

    @Test
    fun linkedAndUnknownAppPayloadsAreNotTransplanted() {
        val xmp = jpegSegment(0xFFE1, "http://ns.adobe.com/xap/1.0/\u0000MotionPhoto".toByteArray())
        val mpf = jpegSegment(0xFFE2, "MPF\u0000linked-image".toByteArray())
        val unknown = jpegSegment(0xFFEB, "unknown".toByteArray())
        val source = testJpeg(
            headerSegments = listOf(
                jpegSegment(0xFFE1, exifPayload()),
                xmp,
                mpf,
                unknown
            )
        )
        val output = ByteArrayOutputStream()

        val result = rewriteJpegWithSourceMetadata(
            source = ByteArrayInputStream(source),
            encoded = ByteArrayInputStream(testJpeg()),
            output = output,
            expectedDimensions = JpegDimensions(32, 24)
        )

        assertTrue(result is MetadataRewriteResult.Rewritten)
        assertFalse(output.toByteArray().containsSubarray(xmp))
        assertFalse(output.toByteArray().containsSubarray(mpf))
        assertFalse(output.toByteArray().containsSubarray(unknown))
    }

    @Test
    fun incompleteIccChunkSequenceIsRejected() {
        val source = testJpeg(
            headerSegments = listOf(
                jpegSegment(0xFFE2, iccPayload(sequence = 1, chunkCount = 2))
            )
        )

        val result = rewriteJpegWithSourceMetadata(
            source = ByteArrayInputStream(source),
            encoded = ByteArrayInputStream(testJpeg()),
            output = ByteArrayOutputStream(),
            expectedDimensions = JpegDimensions(32, 24)
        )

        assertTrue(result is MetadataRewriteResult.Invalid)
    }

    @Test
    fun noSourceMetadataLeavesEncodedOutputUntouched() {
        val source = testJpeg()
        val destination = testJpeg(
            headerSegments = listOf(jpegSegment(0xFFE0, "JFIF".toByteArray()))
        )
        val output = ByteArrayOutputStream()

        val result = rewriteJpegWithSourceMetadata(
            source = ByteArrayInputStream(source),
            encoded = ByteArrayInputStream(destination),
            output = output,
            expectedDimensions = JpegDimensions(32, 24)
        )

        assertEquals(MetadataRewriteResult.NoMetadata, result)
        assertEquals(0, output.size())
    }

    @Test
    fun malformedHeadersAndChangedDimensionsAreRejected() {
        val source = testJpeg(
            width = 33,
            headerSegments = listOf(jpegSegment(0xFFE1, exifPayload()))
        )
        val destination = testJpeg()
        val invalidDestination = destination.copyOf(markerIndex(destination, 0xFFDA) + 3)

        val changed = rewriteJpegWithSourceMetadata(
            source = ByteArrayInputStream(source),
            encoded = ByteArrayInputStream(destination),
            output = ByteArrayOutputStream(),
            expectedDimensions = JpegDimensions(32, 24)
        )
        val malformed = rewriteJpegWithSourceMetadata(
            source = ByteArrayInputStream(testJpeg(headerSegments = listOf(jpegSegment(0xFFE1, exifPayload())))),
            encoded = ByteArrayInputStream(invalidDestination),
            output = ByteArrayOutputStream(),
            expectedDimensions = JpegDimensions(32, 24)
        )

        assertTrue(changed is MetadataRewriteResult.Invalid)
        assertTrue(malformed is MetadataRewriteResult.Invalid)
    }

    @Test
    fun maximumLengthMetadataAndZeroLengthReadsStreamSuccessfully() {
        val maximumPayload = ByteArray(65_533) { index -> (index and 0xFF).toByte() }
        val source = testJpeg(
            headerSegments = listOf(jpegSegment(0xFFFE, maximumPayload))
        )
        val destination = testJpeg()
        val output = ByteArrayOutputStream()

        val result = rewriteJpegWithSourceMetadata(
            source = ShortAndZeroReadInputStream(source),
            encoded = ShortAndZeroReadInputStream(destination),
            output = output,
            expectedDimensions = JpegDimensions(32, 24)
        )

        assertTrue(result is MetadataRewriteResult.Rewritten)
        assertTrue(output.toByteArray().containsSubarray(jpegSegment(0xFFFE, maximumPayload)))
        assertTrue(inspectCompleteJpeg(ByteArrayInputStream(output.toByteArray())) is JpegInspectionResult.Valid)
    }

    private data class TestSegment(
        val marker: Int,
        val bytes: ByteArray
    )

    private fun segmentsBeforeScan(bytes: ByteArray): List<TestSegment> {
        val segments = mutableListOf<TestSegment>()
        var index = 2
        while (index < bytes.lastIndex) {
            val marker = (bytes[index].toInt() and 0xFF) shl 8 or
                (bytes[index + 1].toInt() and 0xFF)
            if (marker == 0xFFDA) break
            val length = (bytes[index + 2].toInt() and 0xFF) shl 8 or
                (bytes[index + 3].toInt() and 0xFF)
            val end = index + 2 + length
            segments += TestSegment(marker, bytes.copyOfRange(index, end))
            index = end
        }
        return segments
    }

    private fun ByteArray.containsSubarray(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        for (start in 0..size - candidate.size) {
            if (candidate.indices.all { offset -> this[start + offset] == candidate[offset] }) return true
        }
        return false
    }

    private fun exifPayload(): ByteArray = "Exif\u0000\u0000test".toByteArray()

    private fun iccPayload(sequence: Int, chunkCount: Int): ByteArray {
        val profileChunkBytes = if (sequence == 1) 128 else 4
        val payload = ByteArray(14 + profileChunkBytes)
        "ICC_PROFILE\u0000".toByteArray().copyInto(payload)
        payload[12] = sequence.toByte()
        payload[13] = chunkCount.toByte()
        if (sequence == 1) {
            payload[17] = 132.toByte()
            "RGB ".toByteArray().copyInto(payload, destinationOffset = 30)
            "acsp".toByteArray().copyInto(payload, destinationOffset = 50)
        }
        return payload
    }
}
