package com.example.slicer.processing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Gives JPEGs without a "date taken" (e.g. from WhatsApp) one, so copies keep a stable gallery date,
 * and corrects the stored resolution of resized photos.
 */
internal fun Context.updateOutputExif(
    sourceUri: Uri,
    jpegFile: File,
    outputDimensions: JpegDimensions?,
    resized: Boolean,
    allowTrailingBytes: Boolean = false
) {
    val candidate = File(jpegFile.parentFile, "${jpegFile.name}.exif")
    try {
        val current = ExifInterface(jpegFile)
        val missingDate = if (current.hasDateTaken()) null else bestDateFor(sourceUri)
        val staleSize = outputDimensions?.takeIf { resized && current.hasSizeTags() }
        if (missingDate == null && staleSize == null) return

        jpegFile.copyTo(candidate, overwrite = true)
        ExifInterface(candidate).apply {
            missingDate?.let(::writeDateTaken)
            staleSize?.let(::writeSize)
            saveAttributes()
        }
        val stillValid = candidate.inputStream().use { input ->
            inspectCompleteJpeg(input, outputDimensions, allowTrailingBytes)
        } is JpegInspectionResult.Valid
        if (stillValid) candidate.renameTo(jpegFile)
    } catch (_: Exception) {
        // Stale photo info is never worth failing the image over.
    } finally {
        candidate.delete()
    }
}

private fun ExifInterface.hasSizeTags() =
    getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION) != null || getAttribute(ExifInterface.TAG_IMAGE_WIDTH) != null

private fun ExifInterface.writeSize(size: JpegDimensions) {
    setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
    setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
    if (getAttribute(ExifInterface.TAG_IMAGE_WIDTH) != null) {
        setAttribute(ExifInterface.TAG_IMAGE_WIDTH, size.width.toString())
        setAttribute(ExifInterface.TAG_IMAGE_LENGTH, size.height.toString())
    }
}

private fun ExifInterface.hasDateTaken() =
    getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != null || getAttribute(ExifInterface.TAG_DATETIME) != null

private fun ExifInterface.writeDateTaken(date: Date) {
    val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date)
    val offset = SimpleDateFormat("XXX", Locale.US).format(date)
    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
    setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
    setAttribute(ExifInterface.TAG_DATETIME, stamp)
    setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
    setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset)
    setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
}

/** A WhatsApp-style name's day survives copies, unlike the file's own time, so it wins. */
private fun Context.bestDateFor(uri: Uri): Date? {
    val (name, modifiedMillis) = nameAndModifiedTime(uri)
    val fileTime = modifiedMillis?.takeIf { it > 0L }?.let(::Date)
    val receivedDay = name?.let(::receivedDayFromName) ?: return fileTime
    return if (fileTime != null && sameDay(receivedDay, fileTime)) fileTime else noonOf(receivedDay)
}

private fun receivedDayFromName(name: String): Date? {
    val digits = WHATSAPP_NAME.find(name)?.groupValues?.get(1) ?: return null
    return runCatching {
        SimpleDateFormat("yyyyMMdd", Locale.US).apply { isLenient = false }.parse(digits)
    }.getOrNull()
}

private fun Context.nameAndModifiedTime(uri: Uri): Pair<String?, Long?> =
    queryNameAndTime(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED, toMillis = 1L)
        ?: queryNameAndTime(uri, MediaStore.MediaColumns.DATE_MODIFIED, toMillis = 1000L)
        ?: (null to null)

private fun Context.queryNameAndTime(uri: Uri, timeColumn: String, toMillis: Long): Pair<String?, Long?>? =
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, timeColumn), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0) to (if (cursor.isNull(1)) null else cursor.getLong(1) * toMillis)
        }
    }.getOrNull()

private fun sameDay(a: Date, b: Date): Boolean {
    val first = Calendar.getInstance().apply { time = a }
    val second = Calendar.getInstance().apply { time = b }
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}

private fun noonOf(day: Date): Date = Calendar.getInstance().apply {
    time = day
    set(Calendar.HOUR_OF_DAY, 12)
}.time

private val WHATSAPP_NAME = Regex("""(20\d{6})-WA\d+""", RegexOption.IGNORE_CASE)
