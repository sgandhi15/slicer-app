package com.example.slicer.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.slicer.core.MOZJPEG_SUBSAMPLING_444
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class MozJpegNativeFdTest {
    @Test
    fun argb8888BitmapEncodesToBorrowedFileDescriptor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File.createTempFile("mozjpeg-direct-", ".jpg", context.cacheDir)
        val bitmap = stripedBitmap()

        try {
            assertTrue(MozJpegNative.runtimeStatusMessage(), MozJpegNative.isAvailable)
            ParcelFileDescriptor.open(output, OUTPUT_MODE).use { descriptor ->
                assertTrue(
                    MozJpegNative.compressBitmapToJpeg(
                        bitmap = bitmap,
                        outputFileDescriptor = descriptor.fd,
                        quality = 95,
                        subsampling = MOZJPEG_SUBSAMPLING_444
                    )
                )
                assertTrue(descriptor.fileDescriptor.valid())
            }

            assertJpegMarkers(output)
            val decoded = requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
            try {
                assertEquals(bitmap.width, decoded.width)
                assertEquals(bitmap.height, decoded.height)
                assertDominantChannel(decoded.getPixel(8, 12), Color.RED)
                assertDominantChannel(decoded.getPixel(24, 12), Color.GREEN)
                assertDominantChannel(decoded.getPixel(40, 12), Color.BLUE)
                assertEquals(Color.RED, bitmap.getPixel(8, 12))
            } finally {
                decoded.recycle()
            }
        } finally {
            bitmap.recycle()
            output.delete()
        }
    }

    @Test
    fun unsupportedBitmapFormatIsRejectedWithoutClosingDescriptor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File.createTempFile("mozjpeg-unsupported-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ALPHA_8)

        try {
            assertTrue(MozJpegNative.runtimeStatusMessage(), MozJpegNative.isAvailable)
            ParcelFileDescriptor.open(output, OUTPUT_MODE).use { descriptor ->
                assertFalse(
                    MozJpegNative.compressBitmapToJpeg(
                        bitmap = bitmap,
                        outputFileDescriptor = descriptor.fd,
                        quality = 80
                    )
                )
                assertTrue(descriptor.fileDescriptor.valid())
            }
            assertEquals(0L, output.length())
        } finally {
            bitmap.recycle()
            output.delete()
        }
    }

    private fun stripedBitmap(): Bitmap {
        return Bitmap.createBitmap(48, 24, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val color = when {
                        x < 16 -> Color.RED
                        x < 32 -> Color.GREEN
                        else -> Color.BLUE
                    }
                    bitmap.setPixel(x, y, color)
                }
            }
        }
    }

    private fun assertJpegMarkers(file: File) {
        assertTrue(file.length() >= 4L)
        RandomAccessFile(file, "r").use { input ->
            assertEquals(0xFF, input.readUnsignedByte())
            assertEquals(0xD8, input.readUnsignedByte())
            input.seek(input.length() - 2L)
            assertEquals(0xFF, input.readUnsignedByte())
            assertEquals(0xD9, input.readUnsignedByte())
        }
    }

    private fun assertDominantChannel(pixel: Int, expected: Int) {
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)
        when (expected) {
            Color.RED -> assertTrue(red > green + 80 && red > blue + 80)
            Color.GREEN -> assertTrue(green > red + 80 && green > blue + 80)
            Color.BLUE -> assertTrue(blue > red + 80 && blue > green + 80)
        }
    }

    private companion object {
        const val OUTPUT_MODE = ParcelFileDescriptor.MODE_WRITE_ONLY or
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
    }
}
