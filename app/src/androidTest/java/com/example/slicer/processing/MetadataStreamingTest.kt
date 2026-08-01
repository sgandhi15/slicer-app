package com.example.slicer.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class MetadataStreamingTest {
    @Test
    fun validatedMetadataSidecarAtomicallyReplacesEncodedFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("metadata-source-", ".jpg", context.cacheDir)
        val encoded = File.createTempFile("metadata-encoded-", ".jpg", context.cacheDir)
        val baseJpeg = createJpeg()
        val app1 = segment(0xFFE1, "Exif\u0000\u0000test".toByteArray())
        source.writeBytes(baseJpeg.copyOfRange(0, 2) + app1 + baseJpeg.copyOfRange(2, baseJpeg.size))
        encoded.writeBytes(baseJpeg)

        try {
            assertTrue(
                context.preserveJpegMetadata(
                    sourceUri = source.toUri(),
                    encodedFile = encoded,
                    expectedDimensions = JpegDimensions(WIDTH, HEIGHT),
                    maximumOutputBytes = source.length()
                )
            )
            assertTrue(encoded.readBytes().containsSubarray(app1))
            assertTrue(
                encoded.inspectCompleteJpeg(JpegDimensions(WIDTH, HEIGHT)) is JpegInspectionResult.Valid
            )
        } finally {
            source.delete()
            encoded.delete()
        }
    }

    @Test
    fun failedMetadataValidationLeavesCanonicalEncodedFileUnchanged() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("metadata-mismatch-source-", ".jpg", context.cacheDir)
        val encoded = File.createTempFile("metadata-mismatch-encoded-", ".jpg", context.cacheDir)
        val baseJpeg = createJpeg()
        val app1 = segment(0xFFE1, "Exif\u0000\u0000test".toByteArray())
        source.writeBytes(baseJpeg.copyOfRange(0, 2) + app1 + baseJpeg.copyOfRange(2, baseJpeg.size))
        encoded.writeBytes(baseJpeg)

        try {
            val preserved = context.preserveJpegMetadata(
                sourceUri = source.toUri(),
                encodedFile = encoded,
                expectedDimensions = JpegDimensions(WIDTH + 1, HEIGHT),
                maximumOutputBytes = source.length()
            )

            assertTrue(!preserved)
            assertArrayEquals(baseJpeg, encoded.readBytes())
        } finally {
            source.delete()
            encoded.delete()
        }
    }

    private fun createJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        try {
            bitmap.eraseColor(Color.rgb(120, 80, 40))
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
        } finally {
            bitmap.recycle()
        }
        return output.toByteArray()
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(
            (marker ushr 8).toByte(),
            marker.toByte(),
            (length ushr 8).toByte(),
            length.toByte()
        ) + payload
    }

    private fun ByteArray.containsSubarray(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        for (start in 0..size - candidate.size) {
            if (candidate.indices.all { offset -> this[start + offset] == candidate[offset] }) return true
        }
        return false
    }

    private companion object {
        const val WIDTH = 24
        const val HEIGHT = 16
    }
}
