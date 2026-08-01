package com.example.slicer.storage

import com.example.slicer.core.DEFAULT_COPY_BUFFER_BYTES
import com.example.slicer.core.DEFAULT_OUTPUT_FOLDER_DISPLAY_NAME
import com.example.slicer.core.DEFAULT_OUTPUT_FOLDER_URI
import com.example.slicer.core.DEFAULT_OUTPUT_RELATIVE_PATH
import com.example.slicer.core.DEFAULT_QUALITY
import com.example.slicer.core.MAX_QUALITY
import com.example.slicer.core.MIN_QUALITY
import com.example.slicer.core.JPEG_EXTENSION_SET
import com.example.slicer.core.JPEG_MIME_TYPE_SET
import com.example.slicer.core.JPEG_MIME_TYPES
import com.example.slicer.core.PREFS_FILE_NAME
import com.example.slicer.core.PREFS_OUTPUT_FOLDER_URI_KEY
import com.example.slicer.core.PREFS_QUALITY_KEY
import com.example.slicer.core.PREFS_WELCOME_SCREEN_SEEN_KEY

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.ceil
import java.util.Locale

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

internal fun Context.canWriteToOutputFolder(uri: Uri): Boolean {
    return outputFolderAccess(uri) == OutputFolderAccess.Writable
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

    return when {
        dot <= 0 -> "$safeBase.jpg"
        safeBase.substring(dot + 1).lowercase(Locale.ROOT) in listOf("jpg", "jpeg") -> safeBase
        else -> "$safeBase.jpg"
    }
}

internal fun Context.uniqueOutputName(baseName: String, usedNames: MutableSet<String>): String {
    if (usedNames.add(baseName)) {
        return baseName
    }

    val dot = baseName.lastIndexOf('.')
    val stem = if (dot > 0) baseName.substring(0, dot) else baseName
    val extension = if (dot > 0) baseName.substring(dot) else ".jpg"

    var index = 1
    while (true) {
        val candidate = "$stem-$index$extension"
        if (usedNames.add(candidate)) {
            return candidate
        }
        index++
    }
}

internal fun Context.queryExistingOutputNames(outputFolderUri: Uri): Set<String> {
    if (outputFolderUri.isDefaultOutputFolder()) {
        return queryExistingDefaultOutputNames()
    }

    return runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(outputFolderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            outputFolderUri,
            documentId
        )

        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        val names = linkedSetOf<String>()

        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (nameIndex >= 0) {
                    cursor.getString(nameIndex)?.let { names.add(it) }
                }
            }
        }

        names
    }.getOrNull() ?: emptySet()
}

internal fun Context.queryExistingDefaultOutputNames(): Set<String> {
    return runCatching {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(DEFAULT_OUTPUT_RELATIVE_PATH)
        val names = linkedSetOf<String>()

        contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (nameIndex >= 0) {
                    cursor.getString(nameIndex)?.let { names.add(it) }
                }
            }
        }

        names
    }.getOrNull() ?: emptySet()
}

internal fun Context.isSupportedJpegUri(uri: Uri): Boolean {
    val mimeType = runCatching { contentResolver.getType(uri) }
        .getOrNull()
        ?.lowercase(Locale.ROOT)
        ?.trim()

    val extension = runCatching {
        resolveDisplayName(uri)
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .trim()
    }.getOrNull() ?: ""

    val hasSupportedMime = mimeType in JPEG_MIME_TYPE_SET
    val hasSupportedExtension = extension in JPEG_EXTENSION_SET
    if (!hasSupportedMime || !hasSupportedExtension) return false

    return hasJpegMagicHeader(uri)
}

internal fun Context.hasJpegMagicHeader(uri: Uri): Boolean {
    return runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(2)
            val readCount = input.read(header)
            readCount == 2 &&
                (header[0].toInt() and 0xFF) == 0xFF &&
                (header[1].toInt() and 0xFF) == 0xD8
        } ?: false
    }.getOrDefault(false)
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
            if (cursor.moveToFirst() && sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
        }
    }.getOrNull() ?: -1L
}

internal fun Context.loadBitmapForCompression(uri: Uri): Bitmap? {
    return runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeStream(input, null, options)
        }
    }.getOrNull()
}

internal fun Context.loadBitmapForPreview(uri: Uri, maxDimension: Int = 1400): Bitmap? {
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

internal fun Context.writeByteArrayToUri(outputUri: Uri, data: ByteArray): Boolean {
    if (data.isEmpty()) return false

    return runCatching {
        contentResolver.openOutputStream(outputUri, "wt")?.use { output ->
            output.write(data)
            output.flush()
        } ?: throw IOException("Could not open output file for writing.")
    }.isSuccess
}

internal suspend fun Context.copyUriContent(sourceUri: Uri, destinationUri: Uri): Long {
    return withContext(Dispatchers.IO) {
        contentResolver.openInputStream(sourceUri)?.use { input ->
            contentResolver.openOutputStream(destinationUri, "wt")?.use { output ->
                val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
                var copiedBytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead == 0) continue
                    output.write(buffer, 0, bytesRead)
                    copiedBytes += bytesRead
                }
                output.flush()
                copiedBytes
            } ?: throw IOException("Could not open output destination")
        } ?: throw IOException("Could not open source file")
    }
}
