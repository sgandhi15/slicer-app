package com.example.slicer.telemetry

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessingPerformanceTelemetryTest {
    @Test
    fun recordsStageTimingCpuIoAndPeakMemory() = runBlocking {
        val source = FakeMeasurementSource()
        val trace = RecordingTrace()
        val recorder = ProcessingMetricsRecorder(
            batchId = "batch",
            itemId = "item",
            attemptCount = 1,
            source = source,
            trace = trace,
            sampleMemoryFrequently = false
        )

        recorder.measure(ProcessingStage.NativeEncode) {
            source.advance(
                elapsedNanos = 8_000_000L,
                cpuMillis = 6L,
                javaHeapBytes = 120L,
                nativeHeapBytes = 90L
            )
        }
        recorder.recordIo(ProcessingStage.NativeEncode, bytesWritten = 45L)
        recorder.recordMemoryDecision(
            estimatedPeakBytes = 256L,
            estimatedAppHeapBytes = 64L,
            rejection = null
        )

        val snapshot = recorder.snapshot()
        val encode = requireNotNull(snapshot.stages[ProcessingStage.NativeEncode])
        assertEquals(8_000_000L, encode.elapsedNanos)
        assertEquals(6L, encode.processCpuMillis)
        assertEquals(1, encode.invocationCount)
        assertEquals(45L, encode.bytesWritten)
        assertEquals(120L, snapshot.peakJavaHeapBytes)
        assertEquals(90L, snapshot.peakNativeHeapBytes)
        assertEquals(256L, snapshot.estimatedPeakBytes)
        assertEquals(64L, snapshot.estimatedAppHeapBytes)
        assertNull(snapshot.memoryRejection)
        assertEquals(1, trace.started.size)
        assertEquals(trace.started, trace.ended)
    }

    @Test
    fun recordsFailedStageAndMemoryRejection() = runBlocking {
        val source = FakeMeasurementSource()
        val recorder = ProcessingMetricsRecorder(
            batchId = null,
            itemId = null,
            attemptCount = 0,
            source = source,
            trace = RecordingTrace(),
            sampleMemoryFrequently = false
        )

        runCatching {
            recorder.measure(ProcessingStage.MemoryAdmission) {
                source.advance(elapsedNanos = 2_000_000L, cpuMillis = 1L)
                error("rejected")
            }
        }
        recorder.recordMemoryDecision(192L, 48L, "InsufficientAppMemory")

        val snapshot = recorder.snapshot()
        assertEquals(1, snapshot.stages[ProcessingStage.MemoryAdmission]?.invocationCount)
        assertEquals("InsufficientAppMemory", snapshot.memoryRejection)
    }

    @Test
    fun disabledRecorderDoesNotSampleOrAccumulate() = runBlocking {
        var called = false

        ProcessingMetricsRecorder.Disabled.measure(ProcessingStage.HeaderRead) {
            called = true
        }
        ProcessingMetricsRecorder.Disabled.recordIo(ProcessingStage.HeaderRead, bytesRead = 12L)

        assertEquals(true, called)
        assertEquals(emptyMap<ProcessingStage, ProcessingStageMetrics>(), ProcessingMetricsRecorder.Disabled.snapshot().stages)
    }

    @Test
    fun aggregateIoSaturatesInsteadOfWrapping() {
        val source = FakeMeasurementSource()
        val recorder = ProcessingMetricsRecorder(
            batchId = null,
            itemId = null,
            attemptCount = 0,
            source = source,
            trace = RecordingTrace(),
            sampleMemoryFrequently = false
        )

        recorder.recordIo(ProcessingStage.HeaderRead, bytesRead = Long.MAX_VALUE)
        recorder.recordIo(ProcessingStage.BitmapDecode, bytesRead = 1L)

        assertEquals(Long.MAX_VALUE, recorder.snapshot().bytesRead)
    }

    private class FakeMeasurementSource : ProcessingMeasurementSource {
        private var elapsedNanos = 0L
        private var cpuMillis = 0L
        private var memory = ProcessMemorySample(javaHeapBytes = 10L, nativeHeapBytes = 20L)

        override fun elapsedRealtimeNanos(): Long = elapsedNanos

        override fun processCpuTimeMillis(): Long = cpuMillis

        override fun memorySample(): ProcessMemorySample = memory

        fun advance(
            elapsedNanos: Long,
            cpuMillis: Long,
            javaHeapBytes: Long = memory.javaHeapBytes,
            nativeHeapBytes: Long = memory.nativeHeapBytes
        ) {
            this.elapsedNanos += elapsedNanos
            this.cpuMillis += cpuMillis
            memory = ProcessMemorySample(javaHeapBytes, nativeHeapBytes)
        }
    }

    private class RecordingTrace : ProcessingTrace {
        val started = mutableListOf<Pair<String, Int>>()
        val ended = mutableListOf<Pair<String, Int>>()

        override fun beginAsyncSection(name: String, cookie: Int) {
            started += name to cookie
        }

        override fun endAsyncSection(name: String, cookie: Int) {
            ended += name to cookie
        }
    }
}
