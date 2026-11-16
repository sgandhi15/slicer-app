package com.example.slicer.processing

import android.app.ActivityManager
import android.content.Context
import java.io.File

internal data class NativeEncodingBudget(
    val bytes: Long,
    val rejection: EncodingMemoryRejection? = null
)

internal fun nativeEncodingBudget(snapshot: EncodingMemorySnapshot): NativeEncodingBudget {
    if (snapshot.isSystemLowMemory) {
        return NativeEncodingBudget(0, EncodingMemoryRejection.SystemLowMemory)
    }
    // Java only coordinates I/O; it no longer holds an image-sized pixel buffer.
    if (snapshot.appAvailableBytes < ENCODING_APP_RESERVE_BYTES) {
        return NativeEncodingBudget(0, EncodingMemoryRejection.InsufficientAppMemory)
    }
    val threshold = snapshot.systemLowMemoryThresholdBytes.coerceIn(0, Long.MAX_VALUE / 2)
    val reserve = maxOf(ENCODING_SYSTEM_RESERVE_BYTES, threshold * 2)
    val budget = (snapshot.systemAvailableBytes.coerceAtLeast(0) - reserve).coerceAtLeast(0)
    return if (budget <= 1024L * 1024) {
        NativeEncodingBudget(0, EncodingMemoryRejection.InsufficientSystemMemory)
    } else {
        NativeEncodingBudget(budget)
    }
}

internal fun Context.nativeEncodingBudget(): NativeEncodingBudget {
    val manager = getSystemService(ActivityManager::class.java)
        ?: return NativeEncodingBudget(0, EncodingMemoryRejection.InsufficientSystemMemory)
    val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    val runtime = Runtime.getRuntime()
    val usedHeap = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0)
    val heapLimit = minOf(runtime.maxMemory(), manager.memoryClass.toLong() * 1024 * 1024)
    return nativeEncodingBudget(
        EncodingMemorySnapshot(
            appAvailableBytes = (heapLimit - usedHeap).coerceAtLeast(0),
            systemAvailableBytes = memory.availMem,
            systemLowMemoryThresholdBytes = memory.threshold,
            isSystemLowMemory = memory.lowMemory
        )
    )
}

internal const val MAX_PARALLEL_ENCODERS = 6
private const val PARALLEL_ENCODER_SHARE_BYTES = 400L * 1024 * 1024

/**
 * How many photos to compress at once: each gets at least a 400 MB share of the native budget (about
 * 60 MP), with one share kept spare for an inline retry. Phones that mix fast and slow cores use one
 * encoder per two cores; phones whose cores are all fast keep only two cores free.
 */
internal fun Context.parallelEncoderCount(): Int {
    val budget = nativeEncodingBudget()
    if (budget.rejection != null) return 1
    val byMemory = (budget.bytes / PARALLEL_ENCODER_SHARE_BYTES - 1).toInt()
    val cores = Runtime.getRuntime().availableProcessors()
    val byCores = maxOf(cores / 2, fastCoreCount(cores) - 2)
    return minOf(byMemory, byCores, MAX_PARALLEL_ENCODERS).coerceAtLeast(1)
}

// Cores whose top speed is within a quarter of the fastest core's; 0 when the speeds can't be read.
private fun fastCoreCount(cores: Int): Int {
    val topSpeeds = (0 until cores).map { core ->
        runCatching {
            File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
        }.getOrDefault(0L)
    }
    val fastest = topSpeeds.maxOrNull() ?: 0L
    if (fastest <= 0L) return 0
    return topSpeeds.count { it * 4 >= fastest * 3 }
}
