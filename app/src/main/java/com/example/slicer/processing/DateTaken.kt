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
 * Photos shared through apps like WhatsApp arrive without a "date taken", so galleries fall back to
 * the file's own date, which resets on every copy. This writes a date into the JPEG so it stays put.
 *
 * Photos that already have a date are never touched. The date is written into a copy, which only
 * replaces [jpegFile] if it is still a complete JPEG; on any problem the file is left as it was.
 */
internal fun Context.addMissingDateTaken(
    sourceUri: Uri,
    jpegFile: File,
    expectedDimensions: JpegDimensions?,
    allowTrailingBytes: Boolean = false
) {
    val candidate = File(jpegFile.parentFile, "${jpegFile.name}.dated")
    try {
        val existing = ExifInterface(jpegFile)
        if (existing.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != null ||
            existing.getAttribute(ExifInterface.TAG_DATETIME) != null
        ) {
            return
        }
        val date = bestDateFor(sourceUri) ?: return

        jpegFile.copyTo(candidate, overwrite = true)
        val exif = ExifInterface(candidate)
        val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date)
        val offset = SimpleDateFormat("XXX", Locale.US).format(date)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
        exif.saveAttributes()

        val stillValid = candidate.inputStream().use { input ->
            inspectCompleteJpeg(input, expectedDimensions, allowTrailingBytes)
        } is JpegInspectionResult.Valid
        if (stillValid && candidate.renameTo(jpegFile)) return
    } catch (_: Exception) {
        // A missing date is not worth failing the image over.
    } finally {
        candidate.delete()
    }
}

/**
 * The date in a WhatsApp-style name (IMG-20241101-WA0012.jpg) is when the photo was received and
 * survives copies, so it wins. The file's own time is used for the time of day when it falls on
 * that same day, and on its own when the name carries no date.
 */
private fun Context.bestDateFor(uri: Uri): Date? {
    val (name, modified) = nameAndModifiedTime(uri)
    val modifiedDate = modified?.takeIf { it > 0L }?.let(::Date)
    val nameDay = name?.let { WHATSAPP_NAME.find(it) }?.groupValues?.get(1)?.let { digits ->
        runCatching {
            SimpleDateFormat("yyyyMMdd", Locale.US).apply { isLenient = false }.parse(digits)
        }.getOrNull()
    } ?: return modifiedDate

    if (modifiedDate != null && sameDay(nameDay, modifiedDate)) return modifiedDate
    return Calendar.getInstance().apply {
        time = nameDay
        set(Calendar.HOUR_OF_DAY, 12)
    }.time
}

private fun Context.nameAndModifiedTime(uri: Uri): Pair<String?, Long?> {
    // Picker results are documents; fall back to MediaStore columns (seconds) for media URIs.
    runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0) to cursor.getLong(1).takeUnless { cursor.isNull(1) }
            }
        }
    }
    runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0) to cursor.getLong(1).takeUnless { cursor.isNull(1) }?.times(1000L)
            }
        }
    }
    return null to null
}

private fun sameDay(a: Date, b: Date): Boolean {
    val first = Calendar.getInstance().apply { time = a }
    val second = Calendar.getInstance().apply { time = b }
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}

private val WHATSAPP_NAME = Regex("""(20\d{6})-WA\d+""", RegexOption.IGNORE_CASE)
