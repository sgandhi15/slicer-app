package com.example.slicer.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.slicer.core.LOG_TAG
import com.example.slicer.core.MOZJPEG_SUBSAMPLING_420
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.telemetry.ProcessingMetricsRecorder
import com.example.slicer.telemetry.ProcessingStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ProcessingPerformanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun representativeTwelveMegapixelPipeline() = runBlocking {
        runRepresentativePipeline(width = 4_000, height = 3_000, label = "12mp")
    }

    @Test
    fun representativeTwentyFourMegapixelPipeline() = runBlocking {
        runRepresentativePipeline(width = 6_000, height = 4_000, label = "24mp")
    }

    @Test
    fun representativeTwelveMegapixelNativeEncode() = runBlocking {
        runRepresentativeNativeEncode(width = 4_000, height = 3_000, label = "12mp-native")
    }

    @Test
    fun representativeTwentyFourMegapixelNativeEncode() = runBlocking {
        runRepresentativeNativeEncode(width = 6_000, height = 4_000, label = "24mp-native")
    }

    @Test
    fun configurableSmallImageBatch() = runBlocking {
        requirePerformanceRun()
        val arguments = InstrumentationRegistry.getArguments()
        val itemCount = arguments.getString(BATCH_SIZE_ARGUMENT)?.toIntOrNull() ?: 100
        require(itemCount in SUPPORTED_BATCH_SIZES) {
            "Batch size must be one of ${SUPPORTED_BATCH_SIZES.joinToString()}."
        }
        val sourceFile = createSourceJpeg(width = 256, height = 192, label = "small")
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val startedCpu = Process.getElapsedCpuTime()
        var peakJavaHeapBytes = currentJavaHeapBytes()
        var peakNativeHeapBytes = Debug.getNativeHeapAllocatedSize()
        var logicalBytesRead = 0L
        var logicalBytesWritten = 0L
        val outputFiles = mutableListOf<File>()

        try {
            repeat(itemCount) { index ->
                val outputFile = File(context.cacheDir, "benchmark-small-output-$index.jpg")
                outputFiles += outputFile
                val metrics = ProcessingMetricsRecorder.create(
                    batchId = "benchmark-small",
                    itemId = "item-$index",
                    attemptCount = 1,
                    sampleMemoryFrequently = true
                )
                val result = processBatchItem(
                    context = context,
                    inputUri = sourceFile.toUri(),
                    tempFile = outputFile,
                    quality = 80,
                    inputBytes = sourceFile.length(),
                    metrics = metrics
                )
                assertEquals(ProcessingDecision.Compressed, result.decision)
                assertTrue(result.outputBytes > 0L)
                val snapshot = metrics.snapshot()
                assertTrue(ProcessingStage.NativeEncode in snapshot.stages)
                logicalBytesRead += snapshot.bytesRead
                logicalBytesWritten += snapshot.bytesWritten
                peakJavaHeapBytes = maxOf(peakJavaHeapBytes, snapshot.peakJavaHeapBytes)
                peakNativeHeapBytes = maxOf(peakNativeHeapBytes, snapshot.peakNativeHeapBytes)
            }
            Log.i(
                LOG_TAG,
                "Batch performance metrics items=$itemCount " +
                    "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L} " +
                    "cpuMs=${Process.getElapsedCpuTime() - startedCpu} " +
                    "javaHeapPeakBytes=$peakJavaHeapBytes nativeHeapPeakBytes=$peakNativeHeapBytes " +
                    "bytesRead=$logicalBytesRead bytesWritten=$logicalBytesWritten"
            )
        } finally {
            sourceFile.delete()
            outputFiles.forEach(File::delete)
        }
        Unit
    }

    private suspend fun runRepresentativePipeline(width: Int, height: Int, label: String) {
        requirePerformanceRun()
        val memoryDecision = context.evaluateEncodingMemory(width, height)
        Log.i(
            LOG_TAG,
            "Memory policy metrics label=$label canEncode=${memoryDecision.canEncode} " +
                "estimatedPeakBytes=${memoryDecision.estimatedPeakBytes} rejection=${memoryDecision.rejection}"
        )
        assumeTrue("Device memory policy rejected $label: ${memoryDecision.rejection}", memoryDecision.canEncode)
        val sourceFile = createSourceJpeg(width, height, label)
        val outputFile = File(context.cacheDir, "benchmark-$label-output.jpg")
        val metrics = ProcessingMetricsRecorder.create(
            batchId = "benchmark-$label",
            itemId = "item",
            attemptCount = 1,
            sampleMemoryFrequently = true
        )

        try {
            val result = processBatchItem(
                context = context,
                inputUri = sourceFile.toUri(),
                tempFile = outputFile,
                quality = 80,
                inputBytes = sourceFile.length(),
                metrics = metrics
            )
            val snapshot = metrics.snapshot()

            assertEquals(ProcessingDecision.Compressed, result.decision)
            assertTrue(result.outputBytes > 0L)
            assertTrue(snapshot.elapsedNanos > 0L)
            assertTrue(snapshot.processCpuMillis > 0L)
            assertTrue(snapshot.bytesRead > 0L)
            assertTrue(snapshot.bytesWritten > 0L)
            assertTrue(ProcessingStage.NativeEncode in snapshot.stages)
            assertTrue(ProcessingStage.Metadata in snapshot.stages)
            metrics.finish(result.decision.name)
        } finally {
            sourceFile.delete()
            outputFile.delete()
        }
    }

    private suspend fun runRepresentativeNativeEncode(width: Int, height: Int, label: String) {
        requirePerformanceRun()
        assumeTrue("MozJPEG native module is unavailable.", MozJpegNative.isAvailable)
        val memoryDecision = context.evaluateEncodingMemory(width, height)
        assumeTrue(
            "Production memory policy rejected $label: ${memoryDecision.rejection}",
            memoryDecision.canEncode
        )
        val bitmapBytes = width.toLong() * height.toLong() * 4L
        val availableJavaHeap = Runtime.getRuntime().let { runtime ->
            runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        }
        assumeTrue(
            "Not enough Java heap to allocate the benchmark bitmap.",
            availableJavaHeap > bitmapBytes + NATIVE_BENCHMARK_HEAP_RESERVE_BYTES
        )
        val outputFile = File(context.cacheDir, "benchmark-$label-output.jpg")
        val metrics = ProcessingMetricsRecorder.create(
            batchId = "benchmark-$label",
            itemId = "item",
            attemptCount = 1,
            sampleMemoryFrequently = true
        )
        var bitmap: Bitmap? = null

        try {
            val benchmarkBitmap = metrics.measure(ProcessingStage.BitmapDecode) {
                createPatternBitmap(width, height)
            }
            bitmap = benchmarkBitmap
            val encoded = metrics.measure(ProcessingStage.NativeEncode) {
                ParcelFileDescriptor.open(outputFile, OUTPUT_FILE_MODE).use { descriptor ->
                    MozJpegNative.compressBitmapToJpeg(
                        bitmap = benchmarkBitmap,
                        outputFileDescriptor = descriptor.fd,
                        quality = 80,
                        progressive = true,
                        subsampling = MOZJPEG_SUBSAMPLING_420,
                        accurateDct = true
                    )
                }
            }
            metrics.recordIo(ProcessingStage.NativeEncode, bytesWritten = outputFile.length())
            assertTrue(encoded)
            val inspection = metrics.measure(ProcessingStage.EncoderValidation) {
                outputFile.inspectCompleteJpeg(JpegDimensions(width, height))
            }
            metrics.recordIo(ProcessingStage.EncoderValidation, bytesRead = outputFile.length())
            assertTrue(inspection is JpegInspectionResult.Valid)
            metrics.finish("DirectEncoded")
        } finally {
            bitmap?.recycle()
            outputFile.delete()
        }
    }

    private fun createSourceJpeg(width: Int, height: Int, label: String): File {
        val sourceFile = File(context.cacheDir, "benchmark-$label-source.jpg")
        val bitmap = createPatternBitmap(width, height)
        try {
            FileOutputStream(sourceFile).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.fd.sync()
            }
        } finally {
            bitmap.recycle()
        }
        return sourceFile
    }

    private fun createPatternBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val stripeHeight = maxOf(1, height / 64)
        repeat(64) { stripe ->
            paint.color = Color.rgb(
                (stripe * 37) and 0xFF,
                (stripe * 73) and 0xFF,
                (stripe * 109) and 0xFF
            )
            canvas.drawRect(
                0f,
                (stripe * stripeHeight).toFloat(),
                width.toFloat(),
                minOf(height, (stripe + 1) * stripeHeight).toFloat(),
                paint
            )
        }
        return bitmap
    }

    private fun requirePerformanceRun() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(PERFORMANCE_ARGUMENT)
            ?.toBooleanStrictOrNull() == true
        assumeTrue(
            "Enable performance scenarios with -e $PERFORMANCE_ARGUMENT true.",
            enabled
        )
    }

    private fun currentJavaHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
    }

    private companion object {
        const val PERFORMANCE_ARGUMENT = "slicer.performance"
        const val BATCH_SIZE_ARGUMENT = "slicer.batchSize"
        const val NATIVE_BENCHMARK_HEAP_RESERVE_BYTES = 64L * 1024L * 1024L
        const val OUTPUT_FILE_MODE = ParcelFileDescriptor.MODE_WRITE_ONLY or
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        val SUPPORTED_BATCH_SIZES = setOf(100, 500, 1_000)
    }
}
