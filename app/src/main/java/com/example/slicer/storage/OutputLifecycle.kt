package com.example.slicer.storage

import com.example.slicer.core.DEFAULT_COPY_BUFFER_BYTES
import com.example.slicer.core.DEFAULT_OUTPUT_RELATIVE_PATH
import com.example.slicer.core.LOG_TAG
import com.example.slicer.core.imageMimeTypeForName
import com.example.slicer.core.RetryableFailure

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

private const val TEMP_OUTPUT_DIRECTORY = "slicer-output-temp"
private val outputLifecycleMutex = Mutex()

internal data class PublishedOutput(
    val uri: Uri,
    val actualName: String,
    val outputBytes: Long
)

internal class OutputPublicationException(
    message: String,
    cause: Throwable,
    val incompleteUri: Uri?,
    val cleanupSucceeded: Boolean
) : IOException(message, cause), RetryableFailure

internal suspend fun <T> withOutputLifecycleLock(block: suspend () -> T): T {
    return outputLifecycleMutex.withLock { block() }
}

internal fun Context.createOutputTempFile(itemId: String): File {
    val directory = File(noBackupFilesDir, TEMP_OUTPUT_DIRECTORY)
    if (!directory.isDirectory && !directory.mkdirs()) {
        throw IOException("Could not create temporary output storage.")
    }

    val safeItemId = itemId.replace("[^A-Za-z0-9._-]".toRegex(), "_").ifBlank { "item" }
    val file = File(directory, "$safeItemId.jpg")
    if (file.exists() && !file.delete()) {
        throw IOException("Could not reset temporary output for this image.")
    }
    return file
}

internal fun Context.deleteOutputTempFile(itemId: String): Boolean {
    val safeItemId = itemId.replace("[^A-Za-z0-9._-]".toRegex(), "_").ifBlank { "item" }
    val file = File(File(noBackupFilesDir, TEMP_OUTPUT_DIRECTORY), "$safeItemId.jpg")
    return !file.exists() || file.delete()
}

internal fun Context.cleanupAbandonedOutputTemps(): Boolean {
    val directory = File(noBackupFilesDir, TEMP_OUTPUT_DIRECTORY)
    if (!directory.exists()) return true

    val files = directory.listFiles() ?: return false
    var cleaned = true
    files.forEach { file ->
        if (file.isFile && !file.delete()) cleaned = false
    }
    return cleaned
}

internal suspend fun Context.publishPreparedOutput(
    tempFile: File,
    outputFolderUri: Uri,
    outputName: String,
    itemId: String,
    onOutputCreationStarting: suspend () -> Unit = {},
    onOutputCleanupConfirmed: suspend () -> Unit = {},
    onOutputCreated: suspend (Uri, String) -> Unit
): PublishedOutput = withContext(Dispatchers.IO) {
    require(tempFile.isFile && tempFile.length() > 0L) {
        "Temporary output is missing or empty."
    }

    var createdUri: Uri? = null
    val isMediaStoreOutput = outputFolderUri.isDefaultOutputFolder()
    try {
        currentCoroutineContext().ensureActive()
        val requestedName = if (isMediaStoreOutput) outputName else recoverableSafOutputName(outputName, itemId)
        val outputUri = withContext(NonCancellable) {
            onOutputCreationStarting()
            val uri = if (isMediaStoreOutput) {
                createPendingMediaStoreOutput(requestedName)
            } else {
                createSafOutput(outputFolderUri, requestedName)
            }
            createdUri = uri
            onOutputCreated(uri, requestedName)
            uri
        }

        val actualName = resolveDisplayName(outputUri)
        if (actualName != requestedName) {
            withContext(NonCancellable) {
                onOutputCreated(outputUri, actualName)
            }
        }
        currentCoroutineContext().ensureActive()

        val copiedBytes = copyPreparedFile(tempFile, outputUri)
        if (copiedBytes != tempFile.length()) {
            throw IOException("Published output size did not match the prepared image.")
        }
        val destinationBytes = resolveContentSize(outputUri)
        if (destinationBytes >= 0L && destinationBytes != copiedBytes) {
            throw IOException("The destination did not retain the complete prepared image.")
        }

        if (isMediaStoreOutput) {
            finalizePendingMediaStoreOutput(outputUri)
        }

        PublishedOutput(
            uri = outputUri,
            actualName = actualName,
            outputBytes = copiedBytes
        )
    } catch (cancelled: CancellationException) {
        val cleaned = withContext(NonCancellable + Dispatchers.IO) {
            createdUri?.let(::deleteOutputDocument) ?: true
        }
        val journalCleared = if (cleaned && createdUri != null) {
            runCatching { withContext(NonCancellable) { onOutputCleanupConfirmed() } }
                .onFailure(cancelled::addSuppressed)
                .isSuccess
        } else {
            false
        }
        if (!cleaned || (createdUri != null && !journalCleared)) {
            cancelled.addSuppressed(IOException("Could not remove incomplete output $createdUri."))
        }
        throw cancelled
    } catch (error: Exception) {
        val creationWasRejected = error is OutputCreationNotPerformedException
        val cleaned = withContext(NonCancellable + Dispatchers.IO) {
            when {
                createdUri != null -> deleteOutputDocument(requireNotNull(createdUri))
                creationWasRejected -> true
                else -> false
            }
        }
        val journalCleared = if (cleaned) {
            runCatching { withContext(NonCancellable) { onOutputCleanupConfirmed() } }
                .onFailure(error::addSuppressed)
                .isSuccess
        } else {
            false
        }
        val cleanupSucceeded = cleaned && journalCleared
        val message = if (cleanupSucceeded) {
            "Could not publish output '$outputName'."
        } else {
            "Could not publish or remove incomplete output '$outputName'."
        }
        throw OutputPublicationException(
            message = message,
            cause = error,
            incompleteUri = createdUri.takeUnless { cleanupSucceeded },
            cleanupSucceeded = cleanupSucceeded
        )
    }
}

internal fun Context.cleanupUntrackedSafOutput(
    outputFolderUri: Uri,
    itemId: String
): Boolean {
    if (outputFolderUri.isDefaultOutputFolder()) return false

    val marker = safStagingMarker(itemId)
    return try {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(outputFolderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(outputFolderUri, treeDocumentId)
        val matchingUris = mutableListOf<Uri>()
        contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex).orEmpty()
                if (marker in name) {
                    matchingUris += DocumentsContract.buildDocumentUriUsingTree(
                        outputFolderUri,
                        cursor.getString(idIndex)
                    )
                }
            }
        } ?: return false

        var cleaned = true
        matchingUris.forEach { uri ->
            if (!deleteOutputDocument(uri)) cleaned = false
        }
        cleaned
    } catch (error: Exception) {
        Log.w(LOG_TAG, "Could not reconcile an untracked SAF output for item '$itemId'.", error)
        false
    }
}

internal fun Context.deleteOutputDocument(uri: Uri): Boolean {
    return try {
        val deleted = if (DocumentsContract.isDocumentUri(this, uri)) {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } else {
            contentResolver.delete(uri, null, null) > 0
        }
        deleted || outputDocumentExists(uri) == false
    } catch (_: FileNotFoundException) {
        true
    } catch (error: Exception) {
        Log.w(LOG_TAG, "Could not delete incomplete output '$uri'.", error)
        false
    }
}

internal fun Context.cleanupOrphanedPendingMediaStoreOutputs(): Boolean {
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val selection = buildString {
        append(MediaStore.MediaColumns.RELATIVE_PATH)
        append(" = ? AND ")
        append(MediaStore.MediaColumns.IS_PENDING)
        append(" = 1 AND ")
        append(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        append(" = ?")
    }
    val args = arrayOf(DEFAULT_OUTPUT_RELATIVE_PATH, packageName)

    return try {
        val pendingUris = mutableListOf<Uri>()
        contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            args,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                pendingUris += ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
            }
        } ?: return false

        var cleaned = true
        pendingUris.forEach { uri ->
            if (!deleteOutputDocument(uri)) cleaned = false
        }
        cleaned
    } catch (error: Exception) {
        Log.w(LOG_TAG, "Could not clean abandoned pending MediaStore outputs.", error)
        false
    }
}

private fun Context.createPendingMediaStoreOutput(fileName: String): Uri {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName.ifBlank { "image.jpg" })
        put(MediaStore.MediaColumns.MIME_TYPE, outputMimeType(fileName))
        put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_OUTPUT_RELATIVE_PATH)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    return contentResolver.insert(collection, values)
        ?: throw OutputCreationNotPerformedException("Could not create pending MediaStore output.")
}

private fun Context.createSafOutput(outputFolderUri: Uri, fileName: String): Uri {
    val folderDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
        outputFolderUri,
        DocumentsContract.getTreeDocumentId(outputFolderUri)
    )
    return DocumentsContract.createDocument(
        contentResolver,
        folderDocumentUri,
        outputMimeType(fileName),
        fileName.ifBlank { "image.jpg" }
    ) ?: throw OutputCreationNotPerformedException("Could not create output in the selected folder.")
}

private fun outputMimeType(fileName: String) = imageMimeTypeForName(fileName) ?: "image/jpeg"

private class OutputCreationNotPerformedException(message: String) : IOException(message)

private fun recoverableSafOutputName(outputName: String, itemId: String): String {
    val dot = outputName.lastIndexOf('.')
    val extension = if (dot > 0) outputName.substring(dot) else ".jpg"
    val stem = if (dot > 0) outputName.substring(0, dot) else outputName
    val marker = safStagingMarker(itemId)
    val maxStemLength = (180 - marker.length - extension.length).coerceAtLeast(1)
    return "${stem.ifBlank { "image" }.take(maxStemLength)}$marker$extension"
}

private fun safStagingMarker(itemId: String): String {
    val safeId = itemId.replace("[^A-Za-z0-9_-]".toRegex(), "_").ifBlank { "item" }
    val token = if (safeId.length <= 48) safeId else "${safeId.take(23)}_${safeId.takeLast(24)}"
    return ".slicer-$token"
}

private fun Context.finalizePendingMediaStoreOutput(uri: Uri) {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.IS_PENDING, 0)
    }
    if (contentResolver.update(uri, values, null, null) != 1) {
        throw IOException("Could not publish pending MediaStore output.")
    }
}

private suspend fun Context.copyPreparedFile(source: File, destinationUri: Uri): Long {
    return withContext(Dispatchers.IO) {
        source.inputStream().use { input ->
            contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
                var copiedBytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead == 0) {
                        val nextByte = input.read()
                        if (nextByte < 0) break
                        output.write(nextByte)
                        copiedBytes++
                    } else {
                        output.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                    }
                }
                output.flush()
                copiedBytes
            } ?: throw IOException("Could not open output destination.")
        }
    }
}

private fun Context.outputDocumentExists(uri: Uri): Boolean? {
    return try {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor -> cursor.moveToFirst() }
    } catch (_: FileNotFoundException) {
        false
    } catch (_: Exception) {
        null
    }
}
