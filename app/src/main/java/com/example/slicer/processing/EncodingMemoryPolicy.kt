package com.example.slicer.processing

import android.app.ActivityManager
import android.content.Context

// Conservative legacy bitmap budget, retained only for platform validation when
// the native library is unavailable. Compression uses NativeEncodingMemoryPolicy.
internal const val ENCODING_ESTIMATED_BYTES_PER_PIXEL = 16L
// Native bitmap and codec allocations are guarded by system headroom, not charged to the Java heap twice.
internal const val ENCODING_ESTIMATED_APP_BYTES_PER_PIXEL = 4L
internal const val ENCODING_APP_RESERVE_BYTES = 64L * 1024L * 1024L
internal const val ENCODING_SYSTEM_RESERVE_BYTES = 256L * 1024L * 1024L
private const val BYTES_PER_MEBIBYTE = 1024L * 1024L
private const val MINIMUM_JPEG_VALIDATION_WORKING_BYTES = 32L * BYTES_PER_MEBIBYTE
private const val MAXIMUM_JPEG_VALIDATION_WORKING_BYTES = 192L * BYTES_PER_MEBIBYTE

internal enum class EncodingMemoryRejection {
    InvalidDimensions,
    ImageTooLarge,
    SystemLowMemory,
    InsufficientAppMemory,
    InsufficientSystemMemory
}

internal data class EncodingMemorySnapshot(
    val appAvailableBytes: Long,
    val systemAvailableBytes: Long,
    val systemLowMemoryThresholdBytes: Long,
    val isSystemLowMemory: Boolean
)

internal data class EncodingMemoryDecision(
    val canEncode: Boolean,
    val estimatedPeakBytes: Long = 0L,
    val estimatedAppHeapBytes: Long = 0L,
    val rejection: EncodingMemoryRejection? = null
)

internal fun evaluateEncodingMemory(
    width: Int,
    height: Int,
    snapshot: EncodingMemorySnapshot
): EncodingMemoryDecision {
    if (width <= 0 || height <= 0) {
        return EncodingMemoryDecision(false, rejection = EncodingMemoryRejection.InvalidDimensions)
    }
    if (width > MAX_JPEG_DIMENSION || height > MAX_JPEG_DIMENSION) {
        return EncodingMemoryDecision(false, rejection = EncodingMemoryRejection.ImageTooLarge)
    }

    val pixels = width.toLong() * height.toLong()
    val estimatedPeakBytes = pixels * ENCODING_ESTIMATED_BYTES_PER_PIXEL
    val estimatedAppHeapBytes = pixels * ENCODING_ESTIMATED_APP_BYTES_PER_PIXEL
    if (snapshot.isSystemLowMemory) {
        return EncodingMemoryDecision(
            canEncode = false,
            estimatedPeakBytes = estimatedPeakBytes,
            estimatedAppHeapBytes = estimatedAppHeapBytes,
            rejection = EncodingMemoryRejection.SystemLowMemory
        )
    }

    val requiredAppBytes = saturatedAdd(estimatedAppHeapBytes, ENCODING_APP_RESERVE_BYTES)
    if (snapshot.appAvailableBytes < requiredAppBytes) {
        return EncodingMemoryDecision(
            canEncode = false,
            estimatedPeakBytes = estimatedPeakBytes,
            estimatedAppHeapBytes = estimatedAppHeapBytes,
            rejection = EncodingMemoryRejection.InsufficientAppMemory
        )
    }

    val thresholdReserve = saturatedMultiply(
        snapshot.systemLowMemoryThresholdBytes.coerceAtLeast(0L),
        2L
    )
    val systemReserve = maxOf(ENCODING_SYSTEM_RESERVE_BYTES, thresholdReserve)
    val requiredSystemBytes = saturatedAdd(estimatedPeakBytes, systemReserve)
    if (snapshot.systemAvailableBytes < requiredSystemBytes) {
        return EncodingMemoryDecision(
            canEncode = false,
            estimatedPeakBytes = estimatedPeakBytes,
            estimatedAppHeapBytes = estimatedAppHeapBytes,
            rejection = EncodingMemoryRejection.InsufficientSystemMemory
        )
    }

    return EncodingMemoryDecision(
        canEncode = true,
        estimatedPeakBytes = estimatedPeakBytes,
        estimatedAppHeapBytes = estimatedAppHeapBytes
    )
}

internal fun Context.evaluateEncodingMemory(width: Int, height: Int): EncodingMemoryDecision {
    val activityManager = getSystemService(ActivityManager::class.java)
        ?: return EncodingMemoryDecision(
            canEncode = false,
            rejection = EncodingMemoryRejection.InsufficientSystemMemory
        )
    val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    val runtime = Runtime.getRuntime()
    val javaHeapUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
    val configuredHeapLimit = activityManager.memoryClass.toLong() * BYTES_PER_MEBIBYTE
    val appHeapLimit = minOf(runtime.maxMemory(), configuredHeapLimit)

    return evaluateEncodingMemory(
        width = width,
        height = height,
        snapshot = EncodingMemorySnapshot(
            appAvailableBytes = (appHeapLimit - javaHeapUsed).coerceAtLeast(0L),
            systemAvailableBytes = memoryInfo.availMem.coerceAtLeast(0L),
            systemLowMemoryThresholdBytes = memoryInfo.threshold.coerceAtLeast(0L),
            isSystemLowMemory = memoryInfo.lowMemory
        )
    )
}

internal fun Context.jpegValidationWorkingMemoryLimitBytes(): Long {
    val activityManager = getSystemService(ActivityManager::class.java)
        ?: return MINIMUM_JPEG_VALIDATION_WORKING_BYTES
    val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    val thresholdReserve = saturatedMultiply(memoryInfo.threshold.coerceAtLeast(0L), 2L)
    val systemReserve = maxOf(ENCODING_SYSTEM_RESERVE_BYTES, thresholdReserve)
    return (memoryInfo.availMem - systemReserve)
        .coerceIn(MINIMUM_JPEG_VALIDATION_WORKING_BYTES, MAXIMUM_JPEG_VALIDATION_WORKING_BYTES)
}

private fun saturatedAdd(left: Long, right: Long): Long {
    if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) return Long.MAX_VALUE
    return left + right
}

private fun saturatedMultiply(left: Long, right: Long): Long {
    if (left < 0L || right < 0L || (right != 0L && left > Long.MAX_VALUE / right)) {
        return Long.MAX_VALUE
    }
    return left * right
}

private const val MAX_JPEG_DIMENSION = 65_535
