package com.example.slicer.processing

import android.app.ActivityManager
import android.content.Context
import com.example.slicer.core.MAX_COMPRESSION_PIXELS

// Includes RGBA pixels and the worst-case coefficient buffers used by 4:4:4 retries.
internal const val ENCODING_ESTIMATED_BYTES_PER_PIXEL = 16L
// Native bitmap and codec allocations are guarded by system headroom, not charged to the Java heap twice.
internal const val ENCODING_ESTIMATED_APP_BYTES_PER_PIXEL = 4L
internal const val ENCODING_APP_RESERVE_BYTES = 64L * 1024L * 1024L
internal const val ENCODING_SYSTEM_RESERVE_BYTES = 256L * 1024L * 1024L
private const val LOW_RAM_MAX_COMPRESSION_PIXELS = 12_000_000L
private const val BYTES_PER_MEBIBYTE = 1024L * 1024L

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
    val isSystemLowMemory: Boolean,
    val isLowRamDevice: Boolean
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

    val pixels = width.toLong() * height.toLong()
    val pixelLimit = if (snapshot.isLowRamDevice) {
        minOf(MAX_COMPRESSION_PIXELS, LOW_RAM_MAX_COMPRESSION_PIXELS)
    } else {
        MAX_COMPRESSION_PIXELS
    }
    if (pixels <= 0L || pixels > pixelLimit) {
        return EncodingMemoryDecision(false, rejection = EncodingMemoryRejection.ImageTooLarge)
    }

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
            isSystemLowMemory = memoryInfo.lowMemory,
            isLowRamDevice = activityManager.isLowRamDevice
        )
    )
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
