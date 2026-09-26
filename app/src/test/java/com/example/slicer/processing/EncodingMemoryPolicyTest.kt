package com.example.slicer.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodingMemoryPolicyTest {
    @Test
    fun allowsImageWhenAppAndSystemHeadroomAreComfortable() {
        val decision = evaluateEncodingMemory(
            width = 4_000,
            height = 3_000,
            snapshot = ampleMemory()
        )

        assertTrue(decision.canEncode)
        assertEquals(192_000_000L, decision.estimatedPeakBytes)
        assertEquals(48_000_000L, decision.estimatedAppHeapBytes)
        assertNull(decision.rejection)
    }

    @Test
    fun rejectsImageWhenAppHeadroomIsBelowAppEstimateAndReserve() {
        val estimatedAppHeap = 4_000L * 3_000L * ENCODING_ESTIMATED_APP_BYTES_PER_PIXEL
        val decision = evaluateEncodingMemory(
            width = 4_000,
            height = 3_000,
            snapshot = ampleMemory().copy(
                appAvailableBytes = estimatedAppHeap + ENCODING_APP_RESERVE_BYTES - 1L
            )
        )

        assertFalse(decision.canEncode)
        assertEquals(EncodingMemoryRejection.InsufficientAppMemory, decision.rejection)
    }

    @Test
    fun rejectsImageWhenSystemHeadroomIsBelowEstimatedPeakAndReserve() {
        val estimatedPeak = 4_000L * 3_000L * ENCODING_ESTIMATED_BYTES_PER_PIXEL
        val decision = evaluateEncodingMemory(
            width = 4_000,
            height = 3_000,
            snapshot = ampleMemory().copy(
                systemAvailableBytes = estimatedPeak + ENCODING_SYSTEM_RESERVE_BYTES - 1L
            )
        )

        assertFalse(decision.canEncode)
        assertEquals(EncodingMemoryRejection.InsufficientSystemMemory, decision.rejection)
    }

    @Test
    fun rejectsEncodingWhileAndroidReportsLowMemory() {
        val decision = evaluateEncodingMemory(
            width = 2_000,
            height = 1_500,
            snapshot = ampleMemory().copy(isSystemLowMemory = true)
        )

        assertFalse(decision.canEncode)
        assertEquals(EncodingMemoryRejection.SystemLowMemory, decision.rejection)
    }

    @Test
    fun allowsFiftyMegapixelImageWhenMemoryHeadroomIsComfortable() {
        val decision = evaluateEncodingMemory(
            width = 10_000,
            height = 5_000,
            snapshot = ampleMemory()
        )

        assertTrue(decision.canEncode)
        assertEquals(800_000_000L, decision.estimatedPeakBytes)
        assertEquals(200_000_000L, decision.estimatedAppHeapBytes)
    }

    @Test
    fun rejectsFiftyMegapixelImageWhenMemoryHeadroomIsInsufficient() {
        val decision = evaluateEncodingMemory(
            width = 10_000,
            height = 5_000,
            snapshot = ampleMemory().copy(appAvailableBytes = 250L * 1024L * 1024L)
        )

        assertFalse(decision.canEncode)
        assertEquals(EncodingMemoryRejection.InsufficientAppMemory, decision.rejection)
    }

    @Test
    fun rejectsDimensionsBeyondJpegFormatLimit() {
        val decision = evaluateEncodingMemory(
            width = 65_536,
            height = 1,
            snapshot = ampleMemory()
        )

        assertFalse(decision.canEncode)
        assertEquals(EncodingMemoryRejection.ImageTooLarge, decision.rejection)
    }

    @Test
    fun rejectsInvalidDimensionsWithoutEstimatingMemory() {
        val decision = evaluateEncodingMemory(
            width = 0,
            height = Int.MAX_VALUE,
            snapshot = ampleMemory()
        )

        assertFalse(decision.canEncode)
        assertEquals(0L, decision.estimatedPeakBytes)
        assertEquals(EncodingMemoryRejection.InvalidDimensions, decision.rejection)
    }

    private fun ampleMemory() = EncodingMemorySnapshot(
        appAvailableBytes = 2L * 1024L * 1024L * 1024L,
        systemAvailableBytes = 4L * 1024L * 1024L * 1024L,
        systemLowMemoryThresholdBytes = 128L * 1024L * 1024L,
        isSystemLowMemory = false
    )
}
