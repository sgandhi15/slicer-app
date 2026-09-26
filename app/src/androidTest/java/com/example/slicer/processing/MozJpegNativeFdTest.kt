package com.example.slicer.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class MozJpegNativeFdTest {
    @Test
    fun entropyTruncatedJpegIsRejectedByNativeValidation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File.createTempFile("mozjpeg-truncated-", ".jpg", context.cacheDir)
        val bitmap = stripedBitmap()

        try {
            output.outputStream().use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream))
            }
            RandomAccessFile(output, "rw").use { file ->
                file.setLength((file.length() / 2L).coerceAtLeast(4L))
                file.seek(file.length() - 2L)
                file.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
            }

            assertEquals(
                NativeJpegValidationResult.InvalidData,
                output.validateJpegViaMozJpeg(
                    JpegDimensions(bitmap.width, bitmap.height),
                    64L * 1024L * 1024L
                )
            )
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

}
