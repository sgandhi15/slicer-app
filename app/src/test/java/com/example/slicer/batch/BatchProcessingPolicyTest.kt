package com.example.slicer.batch

import com.example.slicer.core.RetryableFailure
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
    fun onlyExplicitTransientFailuresAreRetried() {
        assertFalse(IOException("provider unavailable").isRetryableItemFailure())
        assertTrue(RetryableTestException().isRetryableItemFailure())
        assertFalse(SecurityException("permission lost").isRetryableItemFailure())
        assertFalse(
            RetryableTestException(SecurityException("permission lost"))
                .isRetryableItemFailure()
        )
        assertFalse(IllegalStateException("invalid state").isRetryableItemFailure())
    }

    private class RetryableTestException(cause: Throwable? = null) :
        IOException("temporary", cause), RetryableFailure
}
