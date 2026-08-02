package com.example.slicer.telemetry

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.example.slicer.core.LOG_TAG
import java.io.FilterInputStream
import java.io.InputStream
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class ProcessingStage(val traceName: String) {
    HeaderRead("Slicer JPEG header"),
    MemoryAdmission("Slicer memory admission"),
    BitmapDecode("Slicer bitmap decode"),
    NativeEncode("Slicer native encode"),
    EncoderValidation("Slicer encoder validation"),
    VisualValidation("Slicer visual validation"),
    Metadata("Slicer metadata"),
    OriginalCopy("Slicer original copy"),
    FinalValidation("Slicer final validation"),
    Publication("Slicer publication")
}

internal data class ProcessMemorySample(
    val javaHeapBytes: Long,
    val nativeHeapBytes: Long
)

internal data class ProcessingStageMetrics(
    val invocationCount: Int,
    val elapsedNanos: Long,
    val processCpuMillis: Long,
    val bytesRead: Long,
    val bytesWritten: Long
)

internal data class ProcessingMetricsSnapshot(
    val elapsedNanos: Long,
    val processCpuMillis: Long,
    val peakJavaHeapBytes: Long,
    val peakNativeHeapBytes: Long,
    val estimatedPeakBytes: Long,
    val estimatedAppHeapBytes: Long,
    val memoryRejection: String?,
    val stages: Map<ProcessingStage, ProcessingStageMetrics>
) {
    val bytesRead: Long
        get() = stages.values.fold(0L) { total, stage -> saturatedAdd(total, stage.bytesRead) }

    val bytesWritten: Long
        get() = stages.values.fold(0L) { total, stage -> saturatedAdd(total, stage.bytesWritten) }
}

internal interface ProcessingMeasurementSource {
    fun elapsedRealtimeNanos(): Long

    fun processCpuTimeMillis(): Long

    fun memorySample(): ProcessMemorySample
}

internal interface ProcessingTrace {
    fun beginAsyncSection(name: String, cookie: Int)

    fun endAsyncSection(name: String, cookie: Int)
}

internal class ProcessingMetricsRecorder internal constructor(
    private val batchId: String?,
    private val itemId: String?,
    private val attemptCount: Int,
    private val source: ProcessingMeasurementSource?,
    private val trace: ProcessingTrace?,
    private val sampleMemoryFrequently: Boolean = false
) {
    private data class MutableStageMetrics(
        var invocationCount: Int = 0,
        var elapsedNanos: Long = 0L,
        var processCpuMillis: Long = 0L,
        var bytesRead: Long = 0L,
        var bytesWritten: Long = 0L
    )

    private val startedElapsedNanos = source?.elapsedRealtimeNanos() ?: 0L
    private val startedCpuMillis = source?.processCpuTimeMillis() ?: 0L
    private val stages = EnumMap<ProcessingStage, MutableStageMetrics>(ProcessingStage::class.java)
    private var peakJavaHeapBytes = 0L
    private var peakNativeHeapBytes = 0L
    private var estimatedPeakBytes = 0L
    private var estimatedAppHeapBytes = 0L
    private var memoryRejection: String? = null
    private var finished = false

    init {
        sampleMemory()
    }

    suspend fun <T> measure(stage: ProcessingStage, block: suspend () -> T): T {
        val measurementSource = source ?: return block()
        val startedAt = measurementSource.elapsedRealtimeNanos()
        val startedCpu = measurementSource.processCpuTimeMillis()
        val cookie = nextTraceCookie.getAndIncrement()
        trace?.beginAsyncSection(stage.traceName, cookie)
        sampleMemory()
        return try {
            if (sampleMemoryFrequently &&
                (stage == ProcessingStage.BitmapDecode || stage == ProcessingStage.NativeEncode)
            ) {
                coroutineScope {
                    val sampler = launch(Dispatchers.Default) {
                        while (isActive) {
                            sampleMemory()
                            delay(MEMORY_SAMPLE_INTERVAL_MILLIS)
                        }
                    }
                    try {
                        block()
                    } finally {
                        sampler.cancel()
                    }
                }
            } else {
                block()
            }
        } finally {
            sampleMemory()
            trace?.endAsyncSection(stage.traceName, cookie)
            val metrics = stages.getOrPut(stage, ::MutableStageMetrics)
            metrics.invocationCount++
            metrics.elapsedNanos = saturatedAdd(
                metrics.elapsedNanos,
                (measurementSource.elapsedRealtimeNanos() - startedAt).coerceAtLeast(0L)
            )
            metrics.processCpuMillis = saturatedAdd(
                metrics.processCpuMillis,
                (measurementSource.processCpuTimeMillis() - startedCpu).coerceAtLeast(0L)
            )
        }
    }

    fun recordIo(stage: ProcessingStage, bytesRead: Long = 0L, bytesWritten: Long = 0L) {
        if (source == null) return
        val metrics = stages.getOrPut(stage, ::MutableStageMetrics)
        metrics.bytesRead = saturatedAdd(metrics.bytesRead, bytesRead.coerceAtLeast(0L))
        metrics.bytesWritten = saturatedAdd(metrics.bytesWritten, bytesWritten.coerceAtLeast(0L))
    }

    fun recordMemoryDecision(
        estimatedPeakBytes: Long,
        estimatedAppHeapBytes: Long,
        rejection: String?
    ) {
        if (source == null) return
        this.estimatedPeakBytes = estimatedPeakBytes.coerceAtLeast(0L)
        this.estimatedAppHeapBytes = estimatedAppHeapBytes.coerceAtLeast(0L)
        memoryRejection = rejection
        sampleMemory()
    }

    fun snapshot(): ProcessingMetricsSnapshot {
        val measurementSource = source
        if (measurementSource == null) {
            return ProcessingMetricsSnapshot(0L, 0L, 0L, 0L, 0L, 0L, null, emptyMap())
        }
        sampleMemory()
        return ProcessingMetricsSnapshot(
            elapsedNanos = (measurementSource.elapsedRealtimeNanos() - startedElapsedNanos).coerceAtLeast(0L),
            processCpuMillis = (measurementSource.processCpuTimeMillis() - startedCpuMillis).coerceAtLeast(0L),
            peakJavaHeapBytes = peakJavaHeapBytes,
            peakNativeHeapBytes = peakNativeHeapBytes,
            estimatedPeakBytes = estimatedPeakBytes,
            estimatedAppHeapBytes = estimatedAppHeapBytes,
            memoryRejection = memoryRejection,
            stages = stages.mapValues { (_, value) ->
                ProcessingStageMetrics(
                    invocationCount = value.invocationCount,
                    elapsedNanos = value.elapsedNanos,
                    processCpuMillis = value.processCpuMillis,
                    bytesRead = value.bytesRead,
                    bytesWritten = value.bytesWritten
                )
            }
        )
    }

    fun finish(outcome: String) {
        if (source == null || finished) return
        finished = true
        val snapshot = snapshot()
        val stageSummary = snapshot.stages.entries.joinToString(separator = ",") { (stage, metrics) ->
            "${stage.name}:${metrics.elapsedNanos.toMillis()}:${metrics.invocationCount}"
        }
        Log.i(
            LOG_TAG,
            buildString {
                append("Processing metrics")
                batchId?.let { append(" batch=").append(it) }
                itemId?.let { append(" item=").append(it) }
                append(" attempt=").append(attemptCount)
                append(" outcome=").append(outcome)
                append(" elapsedMs=").append(snapshot.elapsedNanos.toMillis())
                append(" cpuMs=").append(snapshot.processCpuMillis)
                append(" javaHeapPeakBytes=").append(snapshot.peakJavaHeapBytes)
                append(" nativeHeapPeakBytes=").append(snapshot.peakNativeHeapBytes)
                append(" estimatedEncodePeakBytes=").append(snapshot.estimatedPeakBytes)
                append(" estimatedAppHeapBytes=").append(snapshot.estimatedAppHeapBytes)
                snapshot.memoryRejection?.let { append(" memoryRejection=").append(it) }
                append(" bytesRead=").append(snapshot.bytesRead)
                append(" bytesWritten=").append(snapshot.bytesWritten)
                append(" stages=").append(stageSummary)
            }
        )
    }

    @Synchronized
    private fun sampleMemory() {
        val sample = source?.memorySample() ?: return
        peakJavaHeapBytes = maxOf(peakJavaHeapBytes, sample.javaHeapBytes.coerceAtLeast(0L))
        peakNativeHeapBytes = maxOf(peakNativeHeapBytes, sample.nativeHeapBytes.coerceAtLeast(0L))
    }

    internal companion object {
        private val nextTraceCookie = AtomicInteger(1)
        private const val MEMORY_SAMPLE_INTERVAL_MILLIS = 20L

        val Disabled = ProcessingMetricsRecorder(
            batchId = null,
            itemId = null,
            attemptCount = 0,
            source = null,
            trace = null,
            sampleMemoryFrequently = false
        )

        fun create(
            batchId: String,
            itemId: String,
            attemptCount: Int,
            sampleMemoryFrequently: Boolean = false
        ): ProcessingMetricsRecorder {
            return ProcessingMetricsRecorder(
                batchId = batchId,
                itemId = itemId,
                attemptCount = attemptCount,
                source = AndroidProcessingMeasurementSource,
                trace = AndroidProcessingTrace,
                sampleMemoryFrequently = sampleMemoryFrequently
            )
        }
    }
}

internal class MeasuredInputStream(input: InputStream) : FilterInputStream(input) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) bytesRead = saturatedAdd(bytesRead, 1L)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, byteCount: Int): Int {
        val count = super.read(buffer, offset, byteCount)
        if (count > 0) bytesRead = saturatedAdd(bytesRead, count.toLong())
        return count
    }

    override fun skip(byteCount: Long): Long {
        val skipped = super.skip(byteCount)
        if (skipped > 0L) bytesRead = saturatedAdd(bytesRead, skipped)
        return skipped
    }
}

private object AndroidProcessingMeasurementSource : ProcessingMeasurementSource {
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()

    override fun processCpuTimeMillis(): Long = Process.getElapsedCpuTime()

    override fun memorySample(): ProcessMemorySample {
        val runtime = Runtime.getRuntime()
        return ProcessMemorySample(
            javaHeapBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L)
        )
    }
}

private object AndroidProcessingTrace : ProcessingTrace {
    override fun beginAsyncSection(name: String, cookie: Int) = Trace.beginAsyncSection(name, cookie)

    override fun endAsyncSection(name: String, cookie: Int) = Trace.endAsyncSection(name, cookie)
}

private fun saturatedAdd(left: Long, right: Long): Long {
    return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

private fun Long.toMillis(): Long = this / 1_000_000L
