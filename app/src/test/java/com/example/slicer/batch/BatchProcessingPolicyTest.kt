package com.example.slicer.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BatchProcessingPolicyTest {
    @Test
    fun retryDelayUsesCappedExponentialBackoff() {
        assertEquals(10_000L, itemRetryDelayMillis(attemptCount = 1, baseDelayMillis = 10_000L))
        assertEquals(20_000L, itemRetryDelayMillis(attemptCount = 2, baseDelayMillis = 10_000L))
        assertEquals(40_000L, itemRetryDelayMillis(attemptCount = 3, baseDelayMillis = 10_000L))
        assertEquals(160_000L, itemRetryDelayMillis(attemptCount = 20, baseDelayMillis = 10_000L))
    }

    @Test
    fun onlyIoFailuresAreRetried() {
        assertTrue(IOException("provider unavailable").isRetryableItemFailure())
        assertFalse(SecurityException("permission lost").isRetryableItemFailure())
        assertFalse(
            IOException("publication failed", SecurityException("permission lost"))
                .isRetryableItemFailure()
        )
        assertFalse(IllegalStateException("invalid state").isRetryableItemFailure())
    }

    @Test
    fun processingRemainsSingleFlightUntilBenchmarksShowSafeHeadroom() {
        assertEquals(1, MAX_CONCURRENT_IMAGE_PROCESSORS)
        assertEquals(3, MAX_ITEM_ATTEMPTS)
        assertEquals(2, MAX_WORKER_RETRIES)
    }
}
