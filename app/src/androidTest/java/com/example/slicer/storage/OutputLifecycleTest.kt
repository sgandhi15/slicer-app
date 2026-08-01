package com.example.slicer.storage

import com.example.slicer.core.DEFAULT_OUTPUT_FOLDER_URI

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OutputLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val createdUris = mutableListOf<Uri>()

    @After
    fun tearDown() {
        createdUris.forEach(context::deleteOutputDocument)
        context.cleanupAbandonedOutputTemps()
    }

    @Test
    fun mediaStoreOutputRemainsPendingUntilPreparedCopyCompletes() = runBlocking {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val tempFile = context.createOutputTempFile("pending-test")
        tempFile.writeBytes(bytes)
        var pendingDuringCallback = -1

        val output = context.publishPreparedOutput(
            tempFile = tempFile,
            outputFolderUri = DEFAULT_OUTPUT_FOLDER_URI,
            outputName = "slicer-test-${UUID.randomUUID()}.jpg",
            itemId = "pending-test"
        ) { uri, _ ->
            createdUris += uri
            pendingDuringCallback = queryPending(uri)
        }

        assertEquals(1, pendingDuringCallback)
        assertEquals(0, queryPending(output.uri))
        val publishedBytes = context.contentResolver.openInputStream(output.uri)?.use { it.readBytes() }
        assertArrayEquals(bytes, publishedBytes)
    }

    @Test
    fun failedJournalCallbackDeletesPendingOutput() = runBlocking {
        val tempFile = context.createOutputTempFile("callback-failure-test")
        tempFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        var provisionalUri: Uri? = null

        val failure = runCatching {
            context.publishPreparedOutput(
                tempFile = tempFile,
                outputFolderUri = DEFAULT_OUTPUT_FOLDER_URI,
                outputName = "slicer-test-${UUID.randomUUID()}.jpg",
                itemId = "callback-failure-test"
            ) { uri, _ ->
                provisionalUri = uri
                throw IOException("Journal unavailable.")
            }
        }.exceptionOrNull()

        assertTrue(failure is OutputPublicationException)
        assertTrue((failure as OutputPublicationException).cleanupSucceeded)
        assertFalse(outputExists(requireNotNull(provisionalUri)))
    }

    private fun queryPending(uri: Uri): Int {
        return context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.IS_PENDING),
            null,
            null,
            null
        )?.use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING))
        } ?: error("Could not query MediaStore output.")
    }

    private fun outputExists(uri: Uri): Boolean {
        return context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { cursor -> cursor.moveToFirst() }
            ?: false
    }
}
