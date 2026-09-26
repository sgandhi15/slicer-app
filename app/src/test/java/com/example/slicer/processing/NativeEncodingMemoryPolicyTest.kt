package com.example.slicer.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeEncodingMemoryPolicyTest {
    @Test
    fun nativeBudgetDoesNotChargeImagePixelsToJavaHeap() {
        val result = nativeEncodingBudget(snapshot())
        assertNull(result.rejection)
        assertEquals(768L * MIB, result.bytes)
    }

    @Test
    fun reservesTwiceAndroidLowMemoryThreshold() {
        val result = nativeEncodingBudget(snapshot().copy(systemLowMemoryThresholdBytes = 300 * MIB))
        assertEquals(424L * MIB, result.bytes)
    }

    @Test
    fun refusesActualMemoryPressureAndPreservesJavaCoordinationReserve() {
        assertEquals(EncodingMemoryRejection.SystemLowMemory,
            nativeEncodingBudget(snapshot().copy(isSystemLowMemory = true)).rejection)
        assertEquals(EncodingMemoryRejection.InsufficientAppMemory,
            nativeEncodingBudget(snapshot().copy(appAvailableBytes = 63 * MIB)).rejection)
        assertEquals(EncodingMemoryRejection.InsufficientSystemMemory,
            nativeEncodingBudget(snapshot().copy(systemAvailableBytes = 256 * MIB)).rejection)
    }

    @Test
    fun extremeThresholdDoesNotOverflowIntoAnAllowedBudget() {
        assertEquals(EncodingMemoryRejection.InsufficientSystemMemory,
            nativeEncodingBudget(snapshot().copy(systemLowMemoryThresholdBytes = Long.MAX_VALUE)).rejection)
    }

    private fun snapshot() = EncodingMemorySnapshot(64 * MIB, 1024 * MIB, 128 * MIB, false)
    private companion object { const val MIB = 1024L * 1024 }
}
