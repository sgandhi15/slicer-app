package com.example.slicer.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class BitmapHelpersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun flatImageWithDifferentColorFailsVisualComparison() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val unrelated = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val encoded = encode(unrelated, "visual-flat-")

        try {
            assertFalse(encoded.isVisuallyConsistentJpeg(64, 64, source))
        } finally {
            source.recycle()
            unrelated.recycle()
            encoded.delete()
        }
    }

    @Test
    fun jpegEncodedFromTheSourcePassesVisualComparison() {
        val source = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    setPixel(x, y, if (x < width / 2) Color.rgb(220, 40, 30) else Color.rgb(20, 80, 220))
                }
            }
        }
        val encoded = encode(source, "visual-match-")

        try {
            assertTrue(encoded.isVisuallyConsistentJpeg(source.width, source.height, source))
        } finally {
            source.recycle()
            encoded.delete()
        }
    }

    private fun encode(bitmap: Bitmap, prefix: String): File {
        return File.createTempFile(prefix, ".jpg", context.cacheDir).also { file ->
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output))
            }
        }
    }
}
