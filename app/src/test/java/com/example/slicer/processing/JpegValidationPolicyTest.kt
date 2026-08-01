package com.example.slicer.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegValidationPolicyTest {
    @Test
    fun normalPrimaryOutputSkipsVisualDecode() {
        assertFalse(
            requiresVisualJpegValidation(
                isPrimaryAttempt = true,
                progressive = true,
                structure = structure(frameMarker = JPEG_PROGRESSIVE_FRAME_MARKER)
            )
        )
    }

    @Test
    fun alternateOrAnomalousOutputRequiresVisualDecode() {
        assertTrue(
            requiresVisualJpegValidation(
                isPrimaryAttempt = false,
                progressive = true,
                structure = structure(frameMarker = JPEG_PROGRESSIVE_FRAME_MARKER)
            )
        )
        assertTrue(
            requiresVisualJpegValidation(
                isPrimaryAttempt = true,
                progressive = true,
                structure = structure(frameMarker = JPEG_BASELINE_FRAME_MARKER)
            )
        )
        assertTrue(
            requiresVisualJpegValidation(
                isPrimaryAttempt = true,
                progressive = true,
                structure = structure(
                    frameMarker = JPEG_PROGRESSIVE_FRAME_MARKER,
                    componentCount = 1
                )
            )
        )
    }

    private fun structure(
        frameMarker: Int,
        componentCount: Int = 3
    ): JpegStructure {
        return JpegStructure(
            dimensions = JpegDimensions(32, 24),
            frameMarker = frameMarker,
            precision = 8,
            componentCount = componentCount,
            scanCount = 1,
            entropyBytes = 100L,
            totalBytes = 200L
        )
    }
}
