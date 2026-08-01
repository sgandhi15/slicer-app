package com.example.slicer.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.slicer.core.ProcessingDecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class BatchProcessorMemoryFallbackTest {
    @Test
    fun unsafeImageCopiesOriginalOverPartialTemporaryOutput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("unsafe-source-", ".jpg", context.cacheDir)
        val output = File.createTempFile("partial-output-", ".jpg", context.cacheDir)
        val sourceBytes = jpegWithDimensions(width = 6_000, height = 5_000)
        source.writeBytes(sourceBytes)
        output.writeText("partial native output")

        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            assertEquals(6_000, bounds.outWidth)
            assertEquals(5_000, bounds.outHeight)

            val result = processBatchItem(
                context = context,
                inputUri = source.toUri(),
                tempFile = output,
                quality = 80,
                inputBytes = source.length()
            )

            assertEquals(ProcessingDecision.CopiedOriginal, result.decision)
            assertEquals(source.length(), result.outputBytes)
            assertTrue(requireNotNull(result.message).contains("too large"))
            assertArrayEquals(sourceBytes, output.readBytes())
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test
    fun truncatedOriginalCannotPassFinalPreparedOutputValidation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("truncated-source-", ".jpg", context.cacheDir)
        val output = File.createTempFile("truncated-output-", ".jpg", context.cacheDir)
        source.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x10))

        try {
            val failure = runCatching {
                processBatchItem(
                    context = context,
                    inputUri = source.toUri(),
                    tempFile = output,
                    quality = 80,
                    inputBytes = source.length()
                )
            }.exceptionOrNull()

            assertTrue(failure is InvalidJpegDataException)
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun jpegWithDimensions(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
        } finally {
            bitmap.recycle()
        }

        return output.toByteArray().also { jpeg ->
            val startOfFrame = (0 until jpeg.size - 8).firstOrNull { index ->
                (jpeg[index].toInt() and 0xFF) == 0xFF &&
                    (jpeg[index + 1].toInt() and 0xFF) in START_OF_FRAME_MARKERS
            } ?: error("Generated JPEG did not contain a start-of-frame segment.")
            jpeg[startOfFrame + 5] = ((height ushr 8) and 0xFF).toByte()
            jpeg[startOfFrame + 6] = (height and 0xFF).toByte()
            jpeg[startOfFrame + 7] = ((width ushr 8) and 0xFF).toByte()
            jpeg[startOfFrame + 8] = (width and 0xFF).toByte()
        }
    }

    private companion object {
        val START_OF_FRAME_MARKERS = setOf(
            0xC0, 0xC1, 0xC2, 0xC3,
            0xC5, 0xC6, 0xC7,
            0xC9, 0xCA, 0xCB,
            0xCD, 0xCE, 0xCF
        )
    }
}
