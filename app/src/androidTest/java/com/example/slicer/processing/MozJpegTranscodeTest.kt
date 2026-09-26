package com.example.slicer.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.exifinterface.media.ExifInterface
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.telemetry.ProcessingMetricsRecorder
import com.example.slicer.telemetry.ProcessingStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.function.BooleanSupplier

@RunWith(AndroidJUnit4::class)
class MozJpegTranscodeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun baselineAndProgressiveInputProduceFullResolutionProgressiveColorOutput() {
        withFiles { source, output ->
            writeSource(source)
            assertEquals(NativeCompressionStatus.Success, transcode(source, output).status)
            assertOutput(output)
            source.writeBytes(output.readBytes())
            assertEquals(NativeCompressionStatus.Success, transcode(source, output).status)
            assertOutput(output)
        }
    }

    @Test
    fun sharedBudgetRejectsCoefficientBuffersAndNextAttemptCanSucceed() {
        withFiles { source, output ->
            writeSource(source, 1024, 1024)
            val dimensions = JpegDimensions(1024, 1024)
            val rejected = transcode(source, output, dimensions, budget = 4 * MIB)
            assertEquals(NativeCompressionStatus.ResourceLimit, rejected.status)
            assertTrue(rejected.estimatedPeakBytes > 4 * MIB)
            val baseline = transcode(source, output, dimensions)
            assertEquals(NativeCompressionStatus.Success, baseline.status)
            // Progressive input must budget decoder coefficients in addition to encoder arrays.
            source.writeBytes(output.readBytes())
            assertEquals(NativeCompressionStatus.ResourceLimit,
                transcode(source, output, dimensions, budget = baseline.estimatedPeakBytes).status)
            val progressive = transcode(source, output, dimensions)
            assertEquals(NativeCompressionStatus.Success, progressive.status)
            assertTrue(progressive.estimatedPeakBytes > baseline.estimatedPeakBytes)
        }
    }

    @Test
    fun cancellationDuringNativeWorkAndCorruptionHaveDistinctResults() {
        withFiles { source, output ->
            writeSource(source)
            var checks = 0
            assertEquals(NativeCompressionStatus.Cancelled,
                transcode(source, output, cancelled = BooleanSupplier { ++checks > 15 }).status)
            assertTrue(checks > 15)
            val original = source.readBytes()
            source.writeBytes(original.copyOf(original.size - 20))
            assertEquals(NativeCompressionStatus.InvalidData, transcode(source, output).status)
        }
    }

    @Test
    fun complexImageOptimizationHonorsBudgetAndSupportsLateCancellation() {
        withFiles { source, output ->
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val random = java.util.Random(42)
            try {
                val pixels = IntArray(512 * 512) { random.nextInt() or 0xFF000000.toInt() }
                bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 512)
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            } finally { bitmap.recycle() }
            val dimensions = JpegDimensions(512, 512)
            var completeChecks = 0
            val completed = transcode(source, output, dimensions,
                cancelled = BooleanSupplier { completeChecks++; false })
            assertEquals(NativeCompressionStatus.Success, completed.status)
            repeat(3) {
                assertEquals(NativeCompressionStatus.ResourceLimit,
                    transcode(source, output, dimensions, budget = completed.estimatedPeakBytes - 4096).status)
            }
            var checks = 0
            assertEquals(NativeCompressionStatus.Cancelled,
                transcode(source, output, dimensions,
                    cancelled = BooleanSupplier { ++checks >= completeChecks - 2 }).status)
            assertEquals(NativeCompressionStatus.Success, transcode(source, output, dimensions).status)
            assertEquals(NativeJpegValidationResult.Valid, output.validateJpegViaMozJpeg(dimensions, 32 * MIB))
        }
    }

    @Test
    fun mismatchedDimensionsAreRejected() {
        withFiles { source, output ->
            writeSource(source)
            assertEquals(NativeCompressionStatus.InvalidData,
                transcode(source, output, JpegDimensions(65, 48)).status)
        }
    }

    @Test
    fun assetSliceOffsetsAndLengthsAreRespected() {
        withFiles { source, output ->
            writeSource(source)
            val jpeg = source.readBytes()
            source.writeBytes(ByteArray(127) + jpeg + ByteArray(1024))
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                ParcelFileDescriptor.open(output, OUTPUT_MODE).use { target ->
                    val result = MozJpegNative.transcodeJpeg(
                        input.fd, target.fd, JpegDimensions(64, 48), 85, 128 * MIB,
                        inputOffset = 127, inputLength = jpeg.size.toLong()
                    )
                    assertEquals(NativeCompressionStatus.Success, result.status)
                    assertEquals(jpeg.size.toLong(), result.bytesRead)
                }
            }
            assertOutput(output)
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                ParcelFileDescriptor.open(output, OUTPUT_MODE).use { target ->
                    assertEquals(NativeCompressionStatus.InvalidData, MozJpegNative.transcodeJpeg(
                        input.fd, target.fd, JpegDimensions(64, 48), 85, 128 * MIB,
                        inputOffset = 127, inputLength = (jpeg.size - 20).toLong()
                    ).status)
                }
            }
        }
    }

    @Test
    fun grayscaleDecodesAndCmykIsPreservedWithoutGuessingColorConversion() = runBlocking {
        withFiles { source, output ->
            fun fixture(name: String) {
                InstrumentationRegistry.getInstrumentation().context.assets.open("jpeg/$name.jpg").use { input ->
                    source.outputStream().use(input::copyTo)
                }
            }
            fixture("grayscale")
            assertEquals(NativeCompressionStatus.Success, transcode(source, output).status)
            val decoded = requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
            try {
                val pixel = decoded.getPixel(32, 24)
                assertTrue(kotlin.math.abs(Color.red(pixel) - Color.green(pixel)) <= 2)
                assertTrue(kotlin.math.abs(Color.red(pixel) - Color.blue(pixel)) <= 2)
            } finally { decoded.recycle() }
            fixture("cmyk")
            assertEquals(NativeCompressionStatus.Unsupported, transcode(source, output).status)
            val result = processBatchItem(context, source.toUri(), output, 80, source.length())
            assertEquals(ProcessingDecision.CopiedOriginal, result.decision)
            assertArrayEquals(source.readBytes(), output.readBytes())
        }
    }

    @Test
    fun compressionPreservesExifOrientationAndOriginalBytes() = runBlocking {
        withFiles { source, output ->
            val bitmap = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    bitmap.setPixel(x, y, Color.rgb((x * 7) % 256, (y * 11) % 256, (x + y) % 256))
                }
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            } finally { bitmap.recycle() }
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_MAKE, "Slicer test")
                saveAttributes()
            }
            val original = source.readBytes()
            val result = processBatchItem(context, source.toUri(), output, 70, source.length())
            assertEquals(ProcessingDecision.Compressed, result.decision)
            assertArrayEquals(original, source.readBytes())
            val exif = ExifInterface(output)
            assertEquals(ExifInterface.ORIENTATION_ROTATE_90,
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            assertEquals("Slicer test", exif.getAttribute(ExifInterface.TAG_MAKE))
            assertEquals(JpegDimensions(256, 192), result.expectedDimensions)
        }
    }

    @Test
    fun sameInputAndOutputFileIsRejectedWithoutTruncation() {
        withFiles { source, _ ->
            writeSource(source)
            val original = source.readBytes()
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_WRITE).use { file ->
                assertEquals(NativeCompressionStatus.SourceOutputCollision, MozJpegNative.transcodeJpeg(
                    file.fd, file.fd, JpegDimensions(64, 48), 85, 128 * MIB
                ).status)
            }
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test
    fun reported2447MegapixelDimensionsCompressWithoutBitmapDecodeStage() = runBlocking {
        withFiles { source, output ->
            writeSource(source, 4284, 5712)
            val metrics = ProcessingMetricsRecorder.create("large-transcode", "image", 1)
            val result = context.compressJpegImage(source.toUri(), output, 80, metrics = metrics)
            assertTrue(result.message, result.succeeded)
            val dimensions = JpegDimensions(4284, 5712)
            assertEquals(dimensions, result.dimensions)
            assertTrue(output.inspectCompleteJpeg(dimensions) is JpegInspectionResult.Valid)
            assertEquals(NativeJpegValidationResult.Valid,
                output.validateJpegViaMozJpeg(dimensions, 128 * MIB))
            val snapshot = metrics.snapshot()
            assertFalse(snapshot.stages.containsKey(ProcessingStage.BitmapDecode))
            assertEquals(0L, snapshot.estimatedAppHeapBytes)
            assertEquals(1, snapshot.stages[ProcessingStage.NativeEncode]?.invocationCount)
        }
    }

    @Test
    fun sequentialPipeInputIsSupported() {
        withFiles { source, output ->
            writeSource(source)
            val pipe = ParcelFileDescriptor.createPipe()
            val writer = Thread {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(source.readBytes()) }
            }
            writer.start()
            try {
                pipe[0].use { input ->
                    ParcelFileDescriptor.open(output, OUTPUT_MODE).use { target ->
                        assertEquals(NativeCompressionStatus.Success, MozJpegNative.transcodeJpeg(
                            input.fd, target.fd, JpegDimensions(64, 48), 85, 128 * MIB
                        ).status)
                    }
                }
            } finally {
                writer.join(5000)
            }
            assertOutput(output)
        }
    }

    private fun transcode(
        source: File, output: File, dimensions: JpegDimensions = JpegDimensions(64, 48),
        budget: Long = 128 * MIB, cancelled: BooleanSupplier = BooleanSupplier { false }
    ): NativeCompressionResult = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
        ParcelFileDescriptor.open(output, OUTPUT_MODE).use { target ->
            val result = MozJpegNative.transcodeJpeg(input.fd, target.fd, dimensions, 85, budget, cancelled)
            assertTrue(input.fileDescriptor.valid())
            assertTrue(target.fileDescriptor.valid())
            result
        }
    }

    private fun writeSource(file: File, width: Int = 64, height: Int = 48) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(220, 35, 20))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertOutput(file: File) {
        val inspection = file.inspectCompleteJpeg(JpegDimensions(64, 48)) as JpegInspectionResult.Valid
        assertEquals(JPEG_PROGRESSIVE_FRAME_MARKER, inspection.structure.frameMarker)
        assertTrue(inspection.structure.scanCount > 1)
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            assertEquals(64, decoded.width)
            assertEquals(48, decoded.height)
            val pixel = decoded.getPixel(32, 24)
            assertTrue(Color.red(pixel) > Color.green(pixel) + 100)
            assertTrue(Color.red(pixel) > Color.blue(pixel) + 100)
        } finally { decoded.recycle() }
    }

    private inline fun withFiles(block: (File, File) -> Unit) {
        val source = File.createTempFile("transcode-source", ".jpg", context.cacheDir)
        val output = File.createTempFile("transcode-output", ".jpg", context.cacheDir)
        try { block(source, output) } finally { source.delete(); output.delete() }
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val OUTPUT_MODE = ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
    }
}
