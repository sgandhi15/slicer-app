package com.example.slicer.storage

import com.example.slicer.core.JPEG_EXTENSION_SET
import com.example.slicer.core.JPEG_MIME_TYPE_SET

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.IOException
import java.util.Locale

internal enum class ImageSelectionOutcome {
    Supported,
    Unsupported,
    Inaccessible
}

internal fun interface ImageSelectionValidator {
    suspend fun validate(uri: Uri): ImageSelectionOutcome
}

internal class ContentResolverImageSelectionValidator(
    private val context: Context
) : ImageSelectionValidator {
    override suspend fun validate(uri: Uri): ImageSelectionOutcome {
        return try {
            currentCoroutineContext().ensureActive()
            val mimeType = context.contentResolver.getType(uri)
                ?.lowercase(Locale.ROOT)
                ?.trim()
            if (mimeType !in JPEG_MIME_TYPE_SET) return ImageSelectionOutcome.Unsupported

            currentCoroutineContext().ensureActive()
            val displayName = context.querySelectionDisplayName(uri)
            val extension = displayName
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
                .trim()
            if (extension !in JPEG_EXTENSION_SET) return ImageSelectionOutcome.Unsupported

            currentCoroutineContext().ensureActive()
            val hasJpegHeader = context.contentResolver.openInputStream(uri)?.use { input ->
                input.hasJpegHeader()
            } ?: throw IOException("Could not open the selected file.")

            if (hasJpegHeader) ImageSelectionOutcome.Supported else ImageSelectionOutcome.Unsupported
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ImageSelectionOutcome.Inaccessible
        }
    }
}

private suspend fun InputStream.hasJpegHeader(): Boolean {
    val header = ByteArray(2)
    var offset = 0
    while (offset < header.size) {
        currentCoroutineContext().ensureActive()
        val readCount = read(header, offset, header.size - offset)
        if (readCount < 0) return false
        if (readCount == 0) {
            val nextByte = read()
            if (nextByte < 0) return false
            header[offset++] = nextByte.toByte()
        } else {
            offset += readCount
        }
    }
    return (header[0].toInt() and 0xFF) == 0xFF &&
        (header[1].toInt() and 0xFF) == 0xD8
}

private fun Context.querySelectionDisplayName(uri: Uri): String {
    val queriedName = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0) cursor.getString(nameIndex) else null
    }

    return queriedName?.ifBlank { null }
        ?: uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.ifBlank { null }
        ?: throw IOException("Could not read the selected file name.")
}
