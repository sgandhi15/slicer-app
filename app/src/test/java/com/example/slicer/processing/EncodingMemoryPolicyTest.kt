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
    fun appliesSmallerPixelLimitOnLowRamDevices() {
        val regularDevice = evaluateEncodingMemory(
            width = 5_000,
            height = 3_000,
            snapshot = ampleMemory()
        )
        val lowRamDevice = evaluateEncodingMemory(
            width = 5_000,
            height = 3_000,
            snapshot = ampleMemory().copy(isLowRamDevice = true)
        )

        assertTrue(regularDevice.canEncode)
        assertFalse(lowRamDevice.canEncode)
        assertEquals(EncodingMemoryRejection.ImageTooLarge, lowRamDevice.rejection)
    }

    @Test
    fun allowsExactRegularAndLowRamPixelLimits() {
        val regularDevice = evaluateEncodingMemory(
            width = 6_000,
            height = 4_000,
            snapshot = ampleMemory()
        )
        val lowRamDevice = evaluateEncodingMemory(
            width = 4_000,
            height = 3_000,
            snapshot = ampleMemory().copy(isLowRamDevice = true)
        )

        assertTrue(regularDevice.canEncode)
        assertTrue(lowRamDevice.canEncode)
    }

    @Test
    fun rejectsOnePixelBeyondRegularAndLowRamLimits() {
        val regularDevice = evaluateEncodingMemory(
            width = 24_000_001,
            height = 1,
            snapshot = ampleMemory()
        )
        val lowRamDevice = evaluateEncodingMemory(
            width = 12_000_001,
            height = 1,
            snapshot = ampleMemory().copy(isLowRamDevice = true)
        )

        assertEquals(EncodingMemoryRejection.ImageTooLarge, regularDevice.rejection)
        assertEquals(EncodingMemoryRejection.ImageTooLarge, lowRamDevice.rejection)
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
        isSystemLowMemory = false,
        isLowRamDevice = false
    )
}
