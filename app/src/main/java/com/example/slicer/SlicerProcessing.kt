package com.example.slicer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

private val mozEncoderTelemetryLogged = AtomicBoolean(false)
private val mozEncoderFallbackTelemetryLogged = AtomicBoolean(false)

internal fun logEncoderRuntimeStatus() {
    if (mozEncoderTelemetryLogged.compareAndSet(false, true)) {
        Log.i(LOG_TAG, "Encoder telemetry: ${MozJpegNative.runtimeStatusMessage()}")
    }
}

internal suspend fun processImageBatch(
    context: Context,
    inputUris: List<Uri>,
    outputFolderUri: Uri,
    quality: Int,
    onProgress: suspend (ProcessingProgress) -> Unit,
    onFileProcessed: suspend (BatchFileResult) -> Unit
): List<BatchFileResult> = withContext(Dispatchers.IO) {
    if (inputUris.isEmpty()) {
        return@withContext emptyList()
    }

    val usedNames = context.queryExistingOutputNames(outputFolderUri).toMutableSet()
    val results = mutableListOf<BatchFileResult>()

    var compressedCount = 0
    var copiedCount = 0
    var failedCount = 0

    val total = inputUris.size
    withContext(Dispatchers.Main) {
        onProgress(ProcessingProgress(total = total))
    }

    inputUris.forEachIndexed { index, inputUri ->
        val inputName = context.resolveDisplayName(inputUri)
        val safeInputName = sanitizeOutputFileName(inputName)
        val outputName = context.uniqueOutputName(baseName = safeInputName, usedNames = usedNames)
        val inputBytes = context.resolveContentSize(inputUri)

        withContext(Dispatchers.Main) {
            onProgress(
                ProcessingProgress(
                    total = total,
                    completed = index,
                    compressedCount = compressedCount,
                    copiedCount = copiedCount,
                    failedCount = failedCount,
                    currentFile = inputName
                )
            )
        }

        val outputUri = context.createOutputDocument(outputFolderUri, outputName)
        val result = if (outputUri == null) {
            BatchFileResult(
                inputUri = inputUri,
                inputName = inputName,
                outputUri = null,
                decision = ProcessingDecision.Failed,
                inputBytes = inputBytes,
                outputBytes = -1L,
                message = "Could not create output file in selected folder: $outputName. Re-select output folder or try another folder."
            )
        } else {
            processSingleImage(
                context = context,
                inputUri = inputUri,
                inputName = inputName,
                outputUri = outputUri,
                quality = quality,
                inputBytes = inputBytes
            )
        }

        results.add(result)
        withContext(Dispatchers.Main) {
            onFileProcessed(result)
        }
        when (result.decision) {
            ProcessingDecision.Compressed -> compressedCount++
            ProcessingDecision.CopiedOriginal -> copiedCount++
            ProcessingDecision.Failed -> failedCount++
        }

        withContext(Dispatchers.Main) {
            onProgress(
                ProcessingProgress(
                    total = total,
                    completed = index + 1,
                    compressedCount = compressedCount,
                    copiedCount = copiedCount,
                    failedCount = failedCount,
                    currentFile = inputName
                )
            )
        }
    }

    Log.i(
        LOG_TAG,
        "Encoder telemetry: isMozJpegEnabled=${MozJpegNative.isMozJpegFeatureEnabled}, " +
            "fallbackUsages=${copiedCount + failedCount}/${results.size}, " +
            "compressed=${compressedCount}, copied=${copiedCount}, failed=${failedCount}"
    )

    results
}

internal suspend fun Context.compressJpegImage(
    inputUri: Uri,
    outputUri: Uri,
    quality: Int
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap = loadBitmapForCompression(inputUri)
            ?: throw IOException("Unable to decode JPEG data.")
        try {
            val encoded = bitmap.encodeJpegViaMozJpeg(quality)

            val saved = if (encoded != null) {
                writeByteArrayToUri(outputUri, encoded) || writeBitmapToUri(bitmap, outputUri, quality)
            } else {
                writeBitmapToUri(bitmap, outputUri, quality)
            }

            if (!saved) {
                throw IOException("JPEG compression failed.")
            }
        } finally {
            bitmap.recycle()
        }
    }.isSuccess
}

private data class MozJpegAttempt(
    val subsampling: Int,
    val progressive: Boolean,
    val accurateDct: Boolean
) {
    val describe: String
        get() = buildString {
            append("subsampling=")
            append(subsampling)
            append(", progressive=")
            append(progressive)
            append(", accurateDct=")
            append(accurateDct)
        }
}

private fun Bitmap.encodeJpegViaMozJpeg(quality: Int): ByteArray? {
    if (!MozJpegNative.isAvailable) {
        if (mozEncoderFallbackTelemetryLogged.compareAndSet(false, true)) {
            Log.i(
                LOG_TAG,
                "MozJPEG native module unavailable; ${MozJpegNative.runtimeStatusMessage()}; framework fallback will be used."
            )
        }
        return null
    }

    val rgbBytes = toRgbByteArray()
    if (rgbBytes.isEmpty()) {
        if (mozEncoderFallbackTelemetryLogged.compareAndSet(false, true)) {
            Log.w(LOG_TAG, "MozJPEG encoding failed; bitmap pixel extraction returned empty buffer; framework fallback will be used.")
        }
        return null
    }

    val attempts = listOf(
        MozJpegAttempt(subsampling = MOZJPEG_SUBSAMPLING_420, progressive = true, accurateDct = true),
        MozJpegAttempt(subsampling = MOZJPEG_SUBSAMPLING_420, progressive = true, accurateDct = false),
        MozJpegAttempt(subsampling = MOZJPEG_SUBSAMPLING_422, progressive = true, accurateDct = true),
        MozJpegAttempt(subsampling = MOZJPEG_SUBSAMPLING_420, progressive = false, accurateDct = true),
        MozJpegAttempt(subsampling = MOZJPEG_SUBSAMPLING_444, progressive = true, accurateDct = true),
        MozJpegAttempt(subsampling = MOZJPEG_SUBSAMPLING_444, progressive = false, accurateDct = true)
    )

    var failureReason: String? = null
    for (attempt in attempts) {
        val encoded = runCatching {
            MozJpegNative.compressRgbToJpeg(
                rgbPixels = rgbBytes,
                width = width,
                height = height,
                quality = quality,
                progressive = attempt.progressive,
                subsampling = attempt.subsampling,
                accurateDct = attempt.accurateDct
            )
        }.getOrNull()

        if (encoded == null) {
            failureReason = "${attempt.describe} returned no output"
            continue
        }

        if (!encoded.isLikelyUsableJpeg(width, height, this)) {
            failureReason = "${attempt.describe} failed post-encode validation"
            continue
        }

        if (attempt != attempts.first()) {
            Log.i(LOG_TAG, "MozJPEG native output succeeded using fallback attempt: ${attempt.describe}")
        }

        return encoded
    }

    if (mozEncoderFallbackTelemetryLogged.compareAndSet(false, true)) {
        Log.w(LOG_TAG, "MozJPEG encoding failed; $failureReason; framework fallback will be used.")
    }

    return null
}

private fun Bitmap.toRgbByteArray(): ByteArray {
    if (isRecycled || width <= 0 || height <= 0) {
        return ByteArray(0)
    }

    val normalizedBitmap = if (config == Bitmap.Config.ARGB_8888) {
        this
    } else {
        copy(Bitmap.Config.ARGB_8888, false) ?: this
    }

    val imageWidth = width
    val imageHeight = height
    val argb = IntArray(imageWidth * imageHeight)
    normalizedBitmap.getPixels(argb, 0, imageWidth, 0, 0, imageWidth, imageHeight)

    if (normalizedBitmap !== this) {
        normalizedBitmap.recycle()
    }

    val rgbBytes = ByteArray(argb.size * 3)
    var rgbIndex = 0
    for (color in argb) {
        rgbBytes[rgbIndex++] = ((color ushr 16) and 0xFF).toByte()
        rgbBytes[rgbIndex++] = ((color ushr 8) and 0xFF).toByte()
        rgbBytes[rgbIndex++] = (color and 0xFF).toByte()
    }

    return rgbBytes
}

private fun ByteArray.isLikelyUsableJpeg(expectedWidth: Int, expectedHeight: Int, sourceBitmap: Bitmap): Boolean {
    if (isEmpty()) {
        return false
    }

    return runCatching {
        val decoded = BitmapFactory.decodeByteArray(this, 0, size)
            ?: return false

        if (decoded.width != expectedWidth || decoded.height != expectedHeight) {
            decoded.recycle()
            return false
        }

        val sourceSpread = sourceBitmap.sampleLumaRange()
        val decodedSpread = decoded.sampleLumaRange()
        decoded.recycle()

        if (sourceSpread <= 4) {
            true
        } else {
            decodedSpread >= 8 || decodedSpread >= sourceSpread / 4
        }
    }.getOrDefault(false)
}

private fun Bitmap.sampleLumaRange(): Int {
    val sampleCols = 16
    val sampleRows = 16
    val stepX = maxOf(1, width / sampleCols)
    val stepY = maxOf(1, height / sampleRows)

    var minLuma = 255
    var maxLuma = 0

    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = getPixel(x, y)
            val luma = (
                ((pixel ushr 16) and 0xFF) * 299 +
                    ((pixel ushr 8) and 0xFF) * 587 +
                    (pixel and 0xFF) * 114
            ) / 1000

            minLuma = minOf(minLuma, luma)
            maxLuma = maxOf(maxLuma, luma)

            x += stepX
        }
        y += stepY
    }

    return maxLuma - minLuma
}

private suspend fun Context.preserveJpegMetadata(
    sourceUri: Uri,
    destinationUri: Uri
): Boolean = withContext(Dispatchers.IO) {
    val sourceBytes = contentResolver.openInputStream(sourceUri)?.use { it.readBytes() } ?: return@withContext false
    val destinationBytes = contentResolver.openInputStream(destinationUri)?.use { it.readBytes() } ?: return@withContext false

    val sourceParsed = parseJpegMetadataBlocks(sourceBytes) ?: return@withContext false
    if (sourceParsed.metadataSegments.isEmpty()) {
        return@withContext true
    }

    val destinationBounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeByteArray(destinationBytes, 0, destinationBytes.size, destinationBounds)
    if (destinationBounds.outWidth <= 0 || destinationBounds.outHeight <= 0) {
        return@withContext false
    }

    val destinationBitmap = BitmapFactory.decodeByteArray(destinationBytes, 0, destinationBytes.size)
    if (destinationBitmap == null ||
        destinationBitmap.width != destinationBounds.outWidth ||
        destinationBitmap.height != destinationBounds.outHeight
    ) {
        destinationBitmap?.recycle()
        return@withContext false
    }

    try {
        val destinationParsed = parseJpegMetadataBlocks(destinationBytes) ?: return@withContext false

        val mergedBytes = buildJpegWithSourceMetadata(destinationParsed, sourceParsed, destinationBytes)
        if (mergedBytes == null) {
            return@withContext false
        }

        if (!mergedBytes.isLikelyUsableJpeg(destinationBitmap.width, destinationBitmap.height, destinationBitmap)) {
            Log.w(
                LOG_TAG,
                "Metadata merge validation failed; preserving source metadata would change decoded JPEG content."
            )
            return@withContext false
        }

        runCatching {
            contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                output.write(mergedBytes)
            } ?: throw IOException("Could not open destination for metadata copy.")
        }.isSuccess
    } finally {
        destinationBitmap.recycle()
    }
}

private data class ParsedJpegMetadata(
    val nonMetadataSegments: List<ByteArray>,
    val metadataSegments: List<ByteArray>,
    val scanStartIndex: Int
)

private fun parseJpegMetadataBlocks(bytes: ByteArray): ParsedJpegMetadata? {
    if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) {
        return null
    }

    val nonMetadataSegments = mutableListOf<ByteArray>()
    val metadataSegments = mutableListOf<ByteArray>()

    var index = 2
    while (index < bytes.size - 1) {
        if (bytes[index] != 0xFF.toByte()) {
            return null
        }

        val marker = readBigEndianUnsignedShort(bytes, index)
        if (marker == JPEG_END_OF_IMAGE) {
            return ParsedJpegMetadata(
                nonMetadataSegments = nonMetadataSegments,
                metadataSegments = metadataSegments,
                scanStartIndex = index + 2
            )
        }

        if (marker == JPEG_START_OF_IMAGE) {
            index += 2
            continue
        }

        if (marker == JPEG_START_OF_SCAN) {
            val remaining = bytes.size - index
            if (remaining < 6) {
                return null
            }

            val length = readBigEndianUnsignedShort(bytes, index + 2)
            if (length < 2) {
                return null
            }

            if (index + 2 + length > bytes.size) {
                return null
            }

            return ParsedJpegMetadata(
                nonMetadataSegments = nonMetadataSegments,
                metadataSegments = metadataSegments,
                scanStartIndex = index
            )
        }

        if (isStandaloneMarker(marker)) {
            nonMetadataSegments.add(bytes.copyOfRange(index, index + 2))
            index += 2
            continue
        }

        if (index + 4 > bytes.size) {
            return null
        }

        val length = readBigEndianUnsignedShort(bytes, index + 2)
        if (length < 2) {
            return null
        }

        val totalSegmentLength = 2 + length
        val segmentEnd = index + totalSegmentLength
        if (segmentEnd > bytes.size) {
            return null
        }

        val segment = bytes.copyOfRange(index, segmentEnd)
        if (isPreservableMetadataMarker(marker)) {
            metadataSegments.add(segment)
        } else {
            nonMetadataSegments.add(segment)
        }

        index = segmentEnd
    }

    return null
}

private fun isStandaloneMarker(marker: Int): Boolean {
    return (marker in 0xFFD0..0xFFD7) || marker == JPEG_END_OF_IMAGE || marker == 0xFF01
}

private fun isPreservableMetadataMarker(marker: Int): Boolean {
    return marker == JPEG_SEGMENT_COMMENT ||
        (marker in JPEG_SEGMENT_APP0..JPEG_SEGMENT_APPF)
}

private fun buildJpegWithSourceMetadata(
    destinationParsed: ParsedJpegMetadata,
    sourceParsed: ParsedJpegMetadata,
    destinationBytes: ByteArray
): ByteArray? {
    if (destinationParsed.scanStartIndex <= 0 || destinationParsed.scanStartIndex > destinationBytes.size) {
        return null
    }

    val output = ByteArrayOutputStream(destinationBytes.size + sourceParsed.metadataSegments.sumOf { it.size })
    output.write(0xFF)
    output.write(0xD8)

    for (segment in destinationParsed.nonMetadataSegments) {
        output.write(segment)
    }
    for (segment in sourceParsed.metadataSegments) {
        output.write(segment)
    }
    output.write(destinationBytes, destinationParsed.scanStartIndex, destinationBytes.size - destinationParsed.scanStartIndex)

    return output.toByteArray()
}

private fun readBigEndianUnsignedShort(bytes: ByteArray, startIndex: Int): Int {
    return ((bytes[startIndex].toInt() and 0xFF) shl 8) or (bytes[startIndex + 1].toInt() and 0xFF)
}

private suspend fun processSingleImage(
    context: Context,
    inputUri: Uri,
    inputName: String,
    outputUri: Uri,
    quality: Int,
    inputBytes: Long
): BatchFileResult {
    val q = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)

    val compressionSucceeded = runCatching {
        context.compressJpegImage(inputUri, outputUri, q)
    }.isSuccess

    if (!compressionSucceeded) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputName = inputName,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compression failed and fallback copy failed."
        )
    }

    val compressedBytes = context.resolveContentSize(outputUri)
    if (compressedBytes <= 0L) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputName = inputName,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output was invalid."
        )
    }

    if (inputBytes > 0L && compressedBytes > inputBytes) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputName = inputName,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output was larger than source."
        )
    }

    val metadataCopied = context.preserveJpegMetadata(inputUri, outputUri)
    if (!metadataCopied) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputName = inputName,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Metadata could not be copied safely; original file was preserved."
        )
    }

    val finalBytes = context.resolveContentSize(outputUri)
    if (inputBytes > 0L && finalBytes > inputBytes) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputName = inputName,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output with metadata was larger than source."
        )
    }

    if (finalBytes <= 0L) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputName = inputName,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = "Compressed output with metadata was invalid."
        )
    }

    return BatchFileResult(
        inputUri = inputUri,
        inputName = inputName,
        outputUri = outputUri,
        decision = ProcessingDecision.Compressed,
        inputBytes = inputBytes,
        outputBytes = finalBytes,
        message = null
    )
}

private suspend fun fallbackToOriginalOrFail(
    context: Context,
    inputUri: Uri,
    inputName: String,
    inputBytes: Long,
    outputUri: Uri,
    outputLabel: String
): BatchFileResult {
    val copied = context.copyUriContent(inputUri, outputUri)

    return if (copied) {
        BatchFileResult(
            inputUri = inputUri,
            inputName = inputName,
            outputUri = outputUri,
            decision = ProcessingDecision.CopiedOriginal,
            inputBytes = inputBytes,
            outputBytes = context.resolveContentSize(outputUri),
            message = outputLabel
        )
    } else {
        BatchFileResult(
            inputUri = inputUri,
            inputName = inputName,
            outputUri = outputUri,
            decision = ProcessingDecision.Failed,
            inputBytes = inputBytes,
            outputBytes = context.resolveContentSize(outputUri),
            message = "$outputLabel Copy fallback failed."
        )
    }
}

internal fun formatSavedPercentage(inputBytes: Long, outputBytes: Long): String? {
    if (inputBytes <= 0L || outputBytes < 0L || outputBytes > inputBytes) {
        return null
    }

    val saved = inputBytes - outputBytes
    if (saved <= 0L) {
        return "No size reduction"
    }

    val percent = (saved.toDouble() / inputBytes.toDouble()) * 100.0
    val precision = if (inputBytes > 0L && percent < 10.0) 1 else 0
    return "saved ${if (precision == 0) percent.roundToInt() else "%.1f".format(Locale.US, percent)}%"
}

internal fun formatBatchSummary(results: List<BatchFileResult>): String {
    if (results.isEmpty()) {
        return "No files processed."
    }

    val compressed = results.count { it.decision == ProcessingDecision.Compressed }
    val copied = results.count { it.decision == ProcessingDecision.CopiedOriginal }
    val failed = results.count { it.decision == ProcessingDecision.Failed }

    return when {
        failed > 0 -> "Done. Compressed: $compressed, kept original: $copied, failed: $failed."
        else -> "Done. ${compressed + copied} files written."
    }
}

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
        .edit()
        .putBoolean(PREFS_WELCOME_SCREEN_SEEN_KEY, true)
        .apply()
}

internal fun Context.saveQuality(value: Int) {
    val clamped = value.coerceIn(MIN_QUALITY, MAX_QUALITY)
    val prefs = getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    prefs.edit().putInt(PREFS_QUALITY_KEY, clamped).apply()
}

internal fun Context.loadSavedOutputFolderUri(): Uri {
    val rawUri = getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        .getString(PREFS_OUTPUT_FOLDER_URI_KEY, null)

    if (rawUri.isNullOrBlank()) {
        return DEFAULT_OUTPUT_FOLDER_URI
    }

    val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        ?: run {
            clearOutputFolderUri()
            return DEFAULT_OUTPUT_FOLDER_URI
        }

    return if (canWriteToOutputFolder(uri)) {
        uri
    } else {
        clearOutputFolderUri()
        DEFAULT_OUTPUT_FOLDER_URI
    }
}

internal fun Context.saveOutputFolderUri(uri: Uri) {
    getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREFS_OUTPUT_FOLDER_URI_KEY, uri.toString())
        .apply()
}

internal fun Context.clearOutputFolderUri() {
    getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(PREFS_OUTPUT_FOLDER_URI_KEY)
        .apply()
}

internal fun Context.canWriteToOutputFolder(uri: Uri): Boolean {
    if (uri.isDefaultOutputFolder()) {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }

    val hasPersistedWritePermission = contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isWritePermission
    }

    if (!hasPersistedWritePermission) {
        return false
    }

    return runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        contentResolver.query(
            childrenUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            cursor.count >= 0
        } ?: false
    }.getOrDefault(false)
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

internal fun Context.createOutputDocument(
    outputFolderUri: Uri,
    fileName: String
): Uri? {
    if (outputFolderUri.isDefaultOutputFolder()) {
        return createDefaultOutputDocument(fileName)
    }

    val safeBaseName = if (fileName.isBlank()) "image.jpg" else fileName
    val attemptedNames = mutableListOf(safeBaseName)
    val stem = safeBaseName.substringBeforeLast('.').ifBlank { "image" }
    val extension = safeBaseName.substringAfterLast('.', "")
    if (extension.isNotBlank()) {
        attemptedNames.add("${stem}_retry.${extension}")
        attemptedNames.add("${stem}_retry_${System.currentTimeMillis()}.${extension}")
    } else {
        attemptedNames.add("${stem}_retry")
        attemptedNames.add("${stem}_retry_${System.currentTimeMillis()}")
    }

    val candidateFolderUris = buildList {
        add(outputFolderUri)
        runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                outputFolderUri,
                DocumentsContract.getTreeDocumentId(outputFolderUri)
            )
        }.onSuccess { add(it) }.onFailure {
            Log.w(LOG_TAG, "Could not derive document URI for tree $outputFolderUri", it)
        }
    }.distinct()

    for (name in attemptedNames) {
        for (documentUri in candidateFolderUris) {
            val created = runCatching {
                DocumentsContract.createDocument(
                    contentResolver,
                    documentUri,
                    OUTPUT_MIME_TYPE,
                    name
                )
            }.onFailure { error ->
                Log.w(
                    LOG_TAG,
                    "Could not create output file '$name' in '$documentUri': ${error.message}",
                    error
                )
            }.getOrNull()

            if (created != null) {
                if (name != safeBaseName) {
                    Log.i(LOG_TAG, "Using fallback output name '$name' after initial attempt failed for '$fileName'.")
                }
                return created
            }
        }
    }

    Log.e(
        LOG_TAG,
        "Could not create output document after retry attempts in folder '$outputFolderUri' for '$safeBaseName'."
    )
    return null
}

internal fun Context.createDefaultOutputDocument(fileName: String): Uri? {
    val safeBaseName = if (fileName.isBlank()) "image.jpg" else fileName
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, safeBaseName)
        put(MediaStore.MediaColumns.MIME_TYPE, OUTPUT_MIME_TYPE)
        put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_OUTPUT_RELATIVE_PATH)
    }

    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val created = runCatching { contentResolver.insert(collection, values) }.getOrNull()

    if (created == null) {
        Log.w(
            LOG_TAG,
            "Could not create default output file '$safeBaseName' in '$DEFAULT_OUTPUT_RELATIVE_PATH'."
        )
        return null
    }

    return created
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

internal fun Bitmap.createPreviewCompressedBitmap(quality: Int): Bitmap? {
    val clampedQuality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
    val encodedBytes = this.encodeJpegBytesForPreview(clampedQuality)
    if (encodedBytes == null || encodedBytes.isEmpty()) {
        return null
    }

    return runCatching {
        BitmapFactory.decodeByteArray(encodedBytes, 0, encodedBytes.size)
    }.getOrNull()
}

private fun Bitmap.encodeJpegBytesForPreview(quality: Int): ByteArray? {
    val clampedQuality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
    val encoded = encodeJpegViaMozJpeg(clampedQuality)
    if (encoded != null && encoded.isNotEmpty()) {
        return encoded
    }

    val fallback = runCatching {
        val stream = ByteArrayOutputStream()
        val saved = compress(Bitmap.CompressFormat.JPEG, clampedQuality, stream)
        if (!saved) {
            return@runCatching ByteArray(0)
        }
        stream.toByteArray()
    }.getOrNull()

    return if (fallback == null || fallback.isEmpty()) null else fallback
}

internal fun Context.writeBitmapToUri(uriBitmapSource: Bitmap, outputUri: Uri, quality: Int): Boolean {
    return runCatching {
        contentResolver.openOutputStream(outputUri, "w")?.use { output ->
            val saved = uriBitmapSource.compress(Bitmap.CompressFormat.JPEG, quality, output)
            if (!saved) {
                throw IOException("Bitmap compress failed.")
            }
        } ?: throw IOException("Could not open output file for writing.")
    }.isSuccess
}

internal fun Context.writeByteArrayToUri(outputUri: Uri, data: ByteArray): Boolean {
    if (data.isEmpty()) return false

    return runCatching {
        contentResolver.openOutputStream(outputUri, "w")?.use { output ->
            output.write(data)
        } ?: throw IOException("Could not open output file for writing.")
    }.isSuccess
}

internal suspend fun Context.copyUriContent(sourceUri: Uri, destinationUri: Uri): Boolean {
    return withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.openInputStream(sourceUri)?.use { input ->
                contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                    val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) {
                            break
                        }
                        output.write(buffer, 0, bytesRead)
                    }
                } ?: throw IOException("Could not open output destination")
            } ?: throw IOException("Could not open source file")
        }.isSuccess
    }
}

internal fun formatByteCount(bytes: Long): String {
    if (bytes < 0L) return "unknown"
    if (bytes == 0L) return "0 B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0

    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }

    val precision = if (unitIndex == 0) 0 else if (value < 10) 1 else 0
    return if (precision == 0) {
        "${value.roundToInt()} ${units[unitIndex]}"
    } else {
        "%.1f ${units[unitIndex]}".format(Locale.US, value)
    }
}

private fun Context.ensureDestinationContentFolder(): Boolean = true
