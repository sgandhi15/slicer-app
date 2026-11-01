package com.example.slicer.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.core.net.toUri
import kotlinx.coroutines.runBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.function.BooleanSupplier

@RunWith(AndroidJUnit4::class)
class MozJpegDescriptorTest {
    @Test
    fun kotlinEntryDoesNotTruncateSourceBeforeNativeSameFileGuard() = runBlocking {
        withFiles { source, _ ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val original = source.readBytes()
            val failure = runCatching {
                processBatchItem(context, source.toUri(), source, 85, source.length())
            }.exceptionOrNull()
            assertTrue(failure is JpegSourceSafetyException)
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test
    fun zeroOffsetRewindsPreviouslyReadProviderDescriptor() = withFiles { source, output ->
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            ParcelFileDescriptor.open(output, OUTPUT_MODE).use { target ->
                Os.lseek(input.fileDescriptor, 10, OsConstants.SEEK_SET)
                val result = MozJpegNative.transcodeJpeg(input.fd, target.fd, DIMENSIONS, 85, BUDGET)
                assertEquals(NativeCompressionStatus.Success, result.status)
                assertTrue(output.inspectCompleteJpeg(DIMENSIONS) is JpegInspectionResult.Valid)
            }
        }
    }

    @Test
    fun throwingCancellationProbeUnwindsAndLeavesBorrowedDescriptorsOpen() = withFiles { source, output ->
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            ParcelFileDescriptor.open(output, OUTPUT_MODE).use { target ->
                var checks = 0
                val failure = runCatching {
                    MozJpegNative.transcodeJpeg(input.fd, target.fd, DIMENSIONS, 85, BUDGET,
                        BooleanSupplier {
                            if (++checks == 12) throw IllegalStateException("probe failed")
                            false
                        })
                }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertEquals("probe failed", failure?.message)
                assertTrue(input.fileDescriptor.valid())
                assertTrue(target.fileDescriptor.valid())
                // Reusing the descriptors also checks native scope cleanup after a Java exception.
                assertEquals(NativeCompressionStatus.Success,
                    MozJpegNative.transcodeJpeg(input.fd, target.fd, DIMENSIONS, 85, BUDGET).status)
            }
        }
    }

    private inline fun withFiles(block: (File, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("descriptor-source", ".jpg", context.cacheDir)
        val output = File.createTempFile("descriptor-output", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(DIMENSIONS.width, DIMENSIONS.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.RED)
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            block(source, output)
        } finally { source.delete(); output.delete() }
    }

    private companion object {
        val DIMENSIONS = JpegDimensions(64, 48)
        const val BUDGET = 32L * 1024 * 1024
        const val OUTPUT_MODE = ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
    }
}
