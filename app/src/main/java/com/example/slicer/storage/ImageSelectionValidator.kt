package com.example.slicer.storage

import com.example.slicer.core.JPEG_EXTENSION_SET
import com.example.slicer.core.JPEG_MIME_TYPE_SET
import com.example.slicer.core.imageMimeTypeForName
import com.example.slicer.core.videoMimeTypeForName

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InputStream
import java.io.IOException
import java.io.Closeable
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class ImageSelectionOutcome {
    Supported,
    SupportedVideo,
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
            val metadata = context.querySelectionMetadata(uri)
            val mimeType = metadata.mimeType
                ?.lowercase(Locale.ROOT)
                ?.trim()
            val extension = metadata.displayName
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
                .trim()
            if (mimeType?.startsWith("video/") == true) {
                // Videos are re-encoded to MP4; their name only needs a known video extension.
                return if (videoMimeTypeForName(metadata.displayName) != null) {
                    ImageSelectionOutcome.SupportedVideo
                } else {
                    ImageSelectionOutcome.Unsupported
                }
            }
            if (mimeType !in JPEG_MIME_TYPE_SET || extension !in JPEG_EXTENSION_SET) {
                // Other images (HEIC, PNG, WebP...) are copied unchanged. They only need a name
                // whose extension is a known image type, so the copy is saved as the same type.
                val isCopyableImage = mimeType?.startsWith("image/") == true &&
                    extension !in JPEG_EXTENSION_SET &&
                    imageMimeTypeForName(metadata.displayName) != null
                return if (isCopyableImage) ImageSelectionOutcome.Supported else ImageSelectionOutcome.Unsupported
            }

            currentCoroutineContext().ensureActive()
            val hasJpegHeader = withProviderCancellation { cancellation ->
                val asset = context.contentResolver.openAssetFileDescriptor(uri, "r", cancellation.signal)
                    ?: return@withProviderCancellation null
                cancellation.track(asset)
                try {
                    val input = asset.createInputStream()
                    cancellation.track(input)
                    try {
                        input.hasJpegHeader(cancellation.signal)
                    } finally {
                        cancellation.clear(input)
                        input.close()
                    }
                } finally {
                    cancellation.clear(asset)
                    asset.close()
                }
            } ?: throw IOException("Could not open the selected file.")

            if (hasJpegHeader) ImageSelectionOutcome.Supported else ImageSelectionOutcome.Unsupported
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OperationCanceledException) {
            currentCoroutineContext().ensureActive()
            ImageSelectionOutcome.Inaccessible
        } catch (_: Exception) {
            ImageSelectionOutcome.Inaccessible
        }
    }
}

private fun InputStream.hasJpegHeader(cancellationSignal: CancellationSignal): Boolean {
    val header = ByteArray(2)
    var offset = 0
    while (offset < header.size) {
        cancellationSignal.throwIfCanceled()
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

private data class SelectionMetadata(val displayName: String, val mimeType: String)

private suspend fun Context.querySelectionMetadata(uri: Uri): SelectionMetadata {
    return withProviderCancellation { cancellation ->
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null,
            null,
            null,
            cancellation.signal
        )?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("Could not query the selected file.")
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val displayName = if (nameIndex >= 0) cursor.getString(nameIndex)?.ifBlank { null } else null
            val mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex)?.ifBlank { null } else null
            SelectionMetadata(
                displayName = displayName
                    ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
                    ?: throw IOException("Could not read the selected file name."),
                mimeType = mimeType ?: throw IOException("Could not read the selected file type.")
            )
        } ?: throw IOException("Could not query the selected file.")
    }
}

private class ProviderCancellation {
    val signal = CancellationSignal()
    private var activeCloseable: Closeable? = null

    fun <T : Closeable> track(closeable: T): T {
        val tracked = synchronized(this) {
            if (signal.isCanceled) false else {
                activeCloseable = closeable
                true
            }
        }
        if (!tracked) {
            runCatching { closeable.close() }
            signal.throwIfCanceled()
        }
        return closeable
    }

    fun clear(closeable: Closeable) {
        synchronized(this) {
            if (activeCloseable === closeable) activeCloseable = null
        }
    }

    fun cancel() {
        val closeable = synchronized(this) {
            activeCloseable.also { activeCloseable = null }
        }
        signal.cancel()
        runCatching { closeable?.close() }
    }
}

private suspend fun <T> withProviderCancellation(block: (ProviderCancellation) -> T): T {
    return suspendCancellableCoroutine { continuation ->
        val cancellation = ProviderCancellation()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            val result = block(cancellation)
            if (continuation.isActive) continuation.resume(result)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}
