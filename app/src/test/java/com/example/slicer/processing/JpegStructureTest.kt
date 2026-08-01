package com.example.slicer.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class JpegStructureTest {
    @Test
    fun sequentialJpegReportsDimensionsAndCompleteStructure() {
        val jpeg = testJpeg(width = 640, height = 480)

        val result = inspectCompleteJpeg(ByteArrayInputStream(jpeg))

        val structure = (result as JpegInspectionResult.Valid).structure
        assertEquals(JpegDimensions(640, 480), structure.dimensions)
        assertEquals(JPEG_BASELINE_FRAME_MARKER, structure.frameMarker)
        assertEquals(8, structure.precision)
        assertEquals(3, structure.componentCount)
        assertEquals(1, structure.scanCount)
        assertTrue(structure.entropyBytes > 0L)
        assertEquals(jpeg.size.toLong(), structure.totalBytes)
    }

    @Test
    fun progressiveScansAcceptStuffingRestartsAndInterScanSegments() {
        val entropyWithStuffingAndRestart = byteArrayOf(
            0x10,
            0xFF.toByte(), 0x00,
            0x20,
            0xFF.toByte(), 0xD2.toByte(),
            0x30
        )
        val jpeg = testJpeg(
            frameMarker = JPEG_PROGRESSIVE_FRAME_MARKER,
            headerSegments = listOf(
                byteArrayOf(0xFF.toByte()),
                jpegSegment(0xFFE1, byteArrayOf(0x01, 0x02))
            ),
            scans = listOf(
                JpegTestScan(
                    componentIds = intArrayOf(1),
                    entropy = entropyWithStuffingAndRestart,
                    followingSegments = listOf(jpegSegment(0xFFC4, byteArrayOf(0x01)))
                ),
                JpegTestScan(componentIds = intArrayOf(2, 3), entropy = byteArrayOf(0x44, 0x55))
            )
        )

        val result = inspectCompleteJpeg(ByteArrayInputStream(jpeg))

        val structure = (result as JpegInspectionResult.Valid).structure
        assertEquals(JPEG_PROGRESSIVE_FRAME_MARKER, structure.frameMarker)
        assertEquals(2, structure.scanCount)
        assertEquals(6L, structure.entropyBytes)
    }

    @Test
    fun completeInspectionRejectsTruncationAndDimensionMismatch() {
        val jpeg = testJpeg(width = 40, height = 30)

        val truncated = inspectCompleteJpeg(ByteArrayInputStream(jpeg.copyOf(jpeg.size - 2)))
        val mismatched = inspectCompleteJpeg(
            input = ByteArrayInputStream(jpeg),
            expectedDimensions = JpegDimensions(30, 40)
        )

        assertTrue(truncated is JpegInspectionResult.Invalid)
        assertTrue(mismatched is JpegInspectionResult.Invalid)
    }

    @Test
    fun generatedOutputRejectsTrailingBytesButOriginalPolicyAllowsThem() {
        val jpeg = testJpeg(trailingBytes = byteArrayOf(0x01, 0x02, 0x03))

        val strict = inspectCompleteJpeg(ByteArrayInputStream(jpeg))
        val original = inspectCompleteJpeg(
            input = ByteArrayInputStream(jpeg),
            allowTrailingBytes = true
        )

        assertTrue(strict is JpegInspectionResult.Invalid)
        assertTrue(original is JpegInspectionResult.Valid)
    }

    @Test
    fun rejectsInvalidSegmentLengthAndMissingFrame() {
        val invalidLength = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x01,
            0xFF.toByte(), 0xD9.toByte()
        )
        val noFrame = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08,
            0x01, 0x01, 0x00, 0x00, 0x3F, 0x00,
            0x11,
            0xFF.toByte(), 0xD9.toByte()
        )

        assertTrue(inspectCompleteJpeg(ByteArrayInputStream(invalidLength)) is JpegInspectionResult.Invalid)
        assertTrue(inspectCompleteJpeg(ByteArrayInputStream(noFrame)) is JpegInspectionResult.Invalid)
    }

    @Test
    fun headerInspectionStopsBeforeEntropyData() {
        val complete = testJpeg(width = 120, height = 80)
        val entropyStart = markerIndex(complete, 0xFFDA).let { scanIndex ->
            val scanLength = (complete[scanIndex + 2].toInt() and 0xFF) shl 8 or
                (complete[scanIndex + 3].toInt() and 0xFF)
            scanIndex + 2 + scanLength
        }

        val result = inspectJpegHeader(ByteArrayInputStream(complete.copyOf(entropyStart)))

        val structure = (result as JpegInspectionResult.Valid).structure
        assertEquals(JpegDimensions(120, 80), structure.dimensions)
        assertEquals(1, structure.scanCount)
        assertEquals(0L, structure.entropyBytes)
    }

    @Test
    fun shortAndZeroLengthReadsStillMakeProgress() {
        val jpeg = testJpeg(width = 17, height = 19)

        val result = inspectCompleteJpeg(ShortAndZeroReadInputStream(jpeg))

        val structure = (result as JpegInspectionResult.Valid).structure
        assertEquals(JpegDimensions(17, 19), structure.dimensions)
    }

    @Test
    fun fullInspectionRunsCancellationCheckpointsWhileStreaming() {
        val jpeg = testJpeg(
            scans = listOf(JpegTestScan(entropy = ByteArray(40_000) { 0x11 }))
        )
        var checkpoints = 0

        val failure = runCatching {
            inspectCompleteJpeg(
                input = ByteArrayInputStream(jpeg),
                checkpoint = {
                    checkpoints++
                    if (checkpoints == 2) throw TestCancellation()
                }
            )
        }.exceptionOrNull()

        assertTrue(failure is TestCancellation)
    }

    private class TestCancellation : RuntimeException()
}
