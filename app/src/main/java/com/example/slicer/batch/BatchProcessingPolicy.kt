package com.example.slicer.batch

import java.io.IOException

internal const val MAX_CONCURRENT_IMAGE_PROCESSORS = 1
internal const val MAX_WORKER_RETRIES = 2
internal const val MAX_ITEM_ATTEMPTS = 3
private const val MAX_RETRY_DELAY_EXPONENT = 4
private const val MAX_ERROR_CAUSE_DEPTH = 16

internal fun itemRetryDelayMillis(attemptCount: Int, baseDelayMillis: Long): Long {
    require(attemptCount > 0) { "Attempt count must be positive." }
    require(baseDelayMillis > 0L) { "Base retry delay must be positive." }
    val multiplier = 1L shl (attemptCount - 1).coerceAtMost(MAX_RETRY_DELAY_EXPONENT)
    return if (baseDelayMillis > Long.MAX_VALUE / multiplier) {
        Long.MAX_VALUE
    } else {
        baseDelayMillis * multiplier
    }
}

internal fun Throwable.isRetryableItemFailure(): Boolean {
    val hasSecurityCause = generateSequence(this) { error -> error.cause }
        .take(MAX_ERROR_CAUSE_DEPTH)
        .any { error -> error is SecurityException }
    if (hasSecurityCause) return false
    return this is IOException
}
