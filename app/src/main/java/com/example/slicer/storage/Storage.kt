package com.example.slicer.storage

import com.example.slicer.core.DEFAULT_COPY_BUFFER_BYTES
import com.example.slicer.core.DEFAULT_OUTPUT_FOLDER_DISPLAY_NAME
import com.example.slicer.core.DEFAULT_OUTPUT_FOLDER_URI
import com.example.slicer.core.DEFAULT_QUALITY
import com.example.slicer.core.MAX_QUALITY
import com.example.slicer.core.MIN_QUALITY
import com.example.slicer.core.FULL_SIZE
import com.example.slicer.core.MAX_DIMENSION_OPTIONS
import com.example.slicer.core.PREFS_MAX_DIMENSION_KEY
import com.example.slicer.core.PREFS_FILE_NAME
import com.example.slicer.core.PREFS_OUTPUT_FOLDER_URI_KEY
import com.example.slicer.core.PREFS_QUALITY_KEY
import com.example.slicer.core.PREFS_WELCOME_SCREEN_SEEN_KEY
import com.example.slicer.core.DEFAULT_VIDEO_SHORT_SIDE
import com.example.slicer.core.PREFS_VIDEO_QUALITY_KEY
import com.example.slicer.core.PREFS_VIDEO_SHORT_SIDE_KEY
import com.example.slicer.core.VIDEO_SHORT_SIDE_OPTIONS
import com.example.slicer.core.VideoQuality
import com.example.slicer.core.videoMimeTypeForName
import com.example.slicer.core.RetryableFailure

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Size
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.ceil

internal fun Context.loadSavedQuality(): Int {
    val prefs = getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    val savedValue = prefs.getInt(PREFS_QUALITY_KEY, DEFAULT_QUALITY)
    return savedValue.coerceIn(MIN_QUALITY, MAX_QUALITY)
}

internal fun Context.hasSeenWelcomeScreen(): Boolean {
    return getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREFS_WELCOME_SCREEN_SEEN_KEY, false)
}

internal fun Context.markWelcomeScreenSeen() {
    getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        .edit { putBoolean(PREFS_WELCOME_SCREEN_SEEN_KEY, true) }
}

internal fun Context.saveQuality(value: Int) {
    val clamped = value.coerceIn(MIN_QUALITY, MAX_QUALITY)
    val prefs = getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    prefs.edit { putInt(PREFS_QUALITY_KEY, clamped) }
}

internal fun Context.loadSavedMaxDimension(): Int {
    val saved = getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE).getInt(PREFS_MAX_DIMENSION_KEY, FULL_SIZE)
    return saved.takeIf { it in MAX_DIMENSION_OPTIONS } ?: FULL_SIZE
}

internal fun Context.saveMaxDimension(value: Int) {
    getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE).edit { putInt(PREFS_MAX_DIMENSION_KEY, value) }
}

internal fun Context.loadSavedVideoQuality(): VideoQuality {
    val saved = getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE).getString(PREFS_VIDEO_QUALITY_KEY, null)
    return VideoQuality.entries.firstOrNull { it.name == saved } ?: VideoQuality.Balanced
}

internal fun Context.saveVideoQuality(value: VideoQuality) {
    getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE).edit { putString(PREFS_VIDEO_QUALITY_KEY, value.name) }
}

internal fun Context.loadSavedVideoShortSide(): Int {
    val saved = getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        .getInt(PREFS_VIDEO_SHORT_SIDE_KEY, DEFAULT_VIDEO_SHORT_SIDE)
    return saved.takeIf { it in VIDEO_SHORT_SIDE_OPTIONS } ?: DEFAULT_VIDEO_SHORT_SIDE
}

internal fun Context.saveVideoShortSide(value: Int) {
    getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE).edit { putInt(PREFS_VIDEO_SHORT_SIDE_KEY, value) }
}

internal fun Context.listMediaInFolder(treeUri: Uri): List<Uri> {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri)
    )
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME
    )
    val media = mutableListOf<Pair<String, Uri>>()
    contentResolver.query(children, projection, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val mimeType = cursor.getString(1) ?: continue
            if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) continue
            val name = cursor.getString(2).orEmpty()
            media += name to DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
        }
    }
    return media.sortedBy { it.first.lowercase() }.map { it.second }
}

internal fun Context.loadSavedOutputFolderUri(): Uri {
    val rawUri = getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        .getString(PREFS_OUTPUT_FOLDER_URI_KEY, null)

    if (rawUri.isNullOrBlank()) {
        return DEFAULT_OUTPUT_FOLDER_URI
    }

    val uri = runCatching { rawUri.toUri() }.getOrNull()
        ?: run {
            clearOutputFolderUri()
            return DEFAULT_OUTPUT_FOLDER_URI
        }

    return when (outputFolderAccess(uri)) {
        OutputFolderAccess.Writable,
        OutputFolderAccess.TemporarilyUnavailable -> uri

        OutputFolderAccess.PermissionMissing -> {
            clearOutputFolderUri()
            DEFAULT_OUTPUT_FOLDER_URI
        }
    }
}

internal fun Context.saveOutputFolderUri(uri: Uri) {
    getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        .edit { putString(PREFS_OUTPUT_FOLDER_URI_KEY, uri.toString()) }
}

internal fun Context.clearOutputFolderUri() {
    getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        .edit { remove(PREFS_OUTPUT_FOLDER_URI_KEY) }
}

internal enum class OutputFolderAccess {
    Writable,
    PermissionMissing,
    TemporarilyUnavailable
}

internal fun Context.outputFolderAccess(uri: Uri): OutputFolderAccess {
    if (uri.isDefaultOutputFolder()) {
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            OutputFolderAccess.Writable
        } else {
            OutputFolderAccess.TemporarilyUnavailable
        }
    }

    val hasPersistedWritePermission = contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isWritePermission
    }

    if (!hasPersistedWritePermission) {
        return OutputFolderAccess.PermissionMissing
    }

    return try {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        contentResolver.query(
            childrenUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            cursor.count
            OutputFolderAccess.Writable
        } ?: OutputFolderAccess.TemporarilyUnavailable
    } catch (_: Exception) {
        OutputFolderAccess.TemporarilyUnavailable
    }
}

internal fun Uri.isDefaultOutputFolder(): Boolean {
    return this == DEFAULT_OUTPUT_FOLDER_URI
}

internal fun Uri.readableName(): String {
    if (isDefaultOutputFolder()) {
        return DEFAULT_OUTPUT_FOLDER_DISPLAY_NAME
    }

    return lastPathSegment
        ?.substringAfterLast(':')
        ?.substringAfterLast('/')
        ?.ifBlank { null }
        ?: "Selected folder"
}

internal fun Context.resolveDisplayName(uri: Uri): String {
    return runCatching {
        val query = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )
        query?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    }.getOrNull()?.ifBlank { null }
        ?: uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.ifBlank { null }
        ?: "image.jpg"
}

internal fun sanitizeOutputFileName(rawName: String): String {
    val safeBase = rawName
        .trim()
        .replace("[\\/:*?\"<>|]".toRegex(), "_")

    if (safeBase.isBlank()) return "image.jpg"
    val dot = safeBase.lastIndexOf('.')

    // Keep the real extension: images copied unchanged must stay their own type.
    return if (dot <= 0) "$safeBase.jpg" else safeBase
}

internal fun Context.resolveContentSize(uri: Uri): Long {
    val assetLength = runCatching {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
            asset.length
        }
    }.getOrNull()

    if (assetLength != null && assetLength >= 0L) {
        return assetLength
    }

    return runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                cursor.getLong(sizeIndex)
            } else {
                -1L
            }
        }
    }.getOrNull() ?: -1L
}

// Gallery photos already have cached thumbnails; asking for them avoids decoding the full photo.
// Some providers return the tiny preview embedded in the JPEG instead, which looks pixelated.
internal fun Context.loadTileThumbnail(uri: Uri, size: Int = 512): Bitmap? =
    runCatching { contentResolver.loadThumbnail(uri, Size(size, size), null) }.getOrNull()
        ?.takeIf { maxOf(it.width, it.height) >= size / 2 }
        ?: if (isVideoUri(uri)) loadVideoFrame(uri, size, exactFrame = false) else loadBitmapForPreview(uri, size)

internal fun Context.isVideoUri(uri: Uri): Boolean {
    val type = runCatching { contentResolver.getType(uri) }.getOrNull()
        ?: uri.lastPathSegment?.let(::videoMimeTypeForName)
    return type?.startsWith("video/") == true
}

internal fun Context.loadBitmapForPreview(uri: Uri, maxDimension: Int = 1400): Bitmap? {
    if (isVideoUri(uri)) return loadVideoFrame(uri, maxDimension, exactFrame = true)
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
    }

    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) {
        return null
    }

    val maxSide = maxOf(width, height)
    val inSample = if (maxSide <= maxDimension) {
        1
    } else {
        ceil(maxSide.toDouble() / maxDimension.toDouble()).toInt().coerceAtLeast(1)
    }

    return runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val options = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeStream(input, null, options)
        }
    }.getOrNull()?.let { bitmap ->
        resolveBitmapOrientation(uri, bitmap)
    }
}

/** A frame a third of the way in, so an original and its compressed copy show the same moment. */
private fun Context.loadVideoFrame(uri: Uri, maxDimension: Int, exactFrame: Boolean): Bitmap? = runCatching {
    MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(this, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val option = if (exactFrame) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        retriever.getScaledFrameAtTime(durationMs * 1000L / 3L, option, maxDimension, maxDimension)
    }
}.getOrNull()

private fun Context.resolveBitmapOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = contentResolver.openInputStream(uri)?.use { input ->
        try {
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    } ?: ExifInterface.ORIENTATION_NORMAL

    return rotateBitmapForOrientation(bitmap, orientation)
}

private fun rotateBitmapForOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
        return bitmap
    }

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
            matrix.setScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_180 -> {
            matrix.setRotate(180f)
        }

        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setScale(1f, -1f)
        }

        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_90 -> {
            matrix.setRotate(90f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_270 -> {
            matrix.setRotate(270f)
        }

        else -> {
            return bitmap
        }
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrNull() ?: bitmap
}

internal suspend fun Context.copyUriContent(sourceUri: Uri, destinationUri: Uri): Long {
    return try {
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(sourceUri)?.use { input ->
                contentResolver.openOutputStream(destinationUri, "wt")?.use { output ->
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
                } ?: throw FileNotFoundException("Could not open output destination")
            } ?: throw FileNotFoundException("Could not open source file")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (missing: FileNotFoundException) {
        throw missing
    } catch (error: IOException) {
        throw RetryableProviderIOException("Provider I/O was interrupted.", error)
    }
}

internal class RetryableProviderIOException(message: String, cause: IOException) :
    IOException(message, cause), RetryableFailure
