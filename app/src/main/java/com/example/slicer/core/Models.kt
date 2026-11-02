package com.example.slicer.core

import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import java.io.IOException
import java.util.Locale

internal const val DEFAULT_QUALITY = 80
internal const val MIN_QUALITY = 1
internal const val MAX_QUALITY = 100

internal const val PREFS_FILE_NAME = "slicer_preferences"
internal const val PREFS_QUALITY_KEY = "last_quality"
internal const val PREFS_OUTPUT_FOLDER_URI_KEY = "output_folder_uri"
internal const val PREFS_WELCOME_SCREEN_SEEN_KEY = "welcome_screen_seen"
internal const val PREFS_MAX_DIMENSION_KEY = "max_dimension"
internal const val PREFS_VIDEO_QUALITY_KEY = "video_quality"
internal const val PREFS_VIDEO_SHORT_SIDE_KEY = "video_short_side"

internal const val FULL_SIZE = 0
internal val MAX_DIMENSION_OPTIONS = listOf(FULL_SIZE, 3000, 2048, 1280)

/** Videos are limited by their short side, so portrait and landscape clips get the same treatment. */
internal const val ORIGINAL_RESOLUTION = 0
internal const val DEFAULT_VIDEO_SHORT_SIDE = 1080
internal val VIDEO_SHORT_SIDE_OPTIONS = listOf(ORIGINAL_RESOLUTION, 1080, 720, 480)

/** Bits per pixel per frame for HEVC; H.264 needs about half as much again for the same look. */
internal enum class VideoQuality(val label: String, val description: String, val bitsPerPixel: Double) {
    Small("Small", "Prioritize smaller files.", 0.045),
    Balanced("Balanced", "Balance quality and file size.", 0.07),
    Best("Best", "Preserve more video detail.", 0.11)
}

internal val DEFAULT_OUTPUT_FOLDER_URI: Uri = "content://com.example.slicer.default/output/pictures_slicer".toUri()
internal val DEFAULT_OUTPUT_RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/Slicer/"
internal const val DEFAULT_OUTPUT_FOLDER_DISPLAY_NAME = "Pictures/Slicer"

internal val JPEG_MIME_TYPES = arrayOf("image/jpeg", "image/jpg")
internal val JPEG_EXTENSIONS = arrayOf("jpg", "jpeg")
internal val JPEG_MIME_TYPE_SET = JPEG_MIME_TYPES.toSet()
internal val JPEG_EXTENSION_SET = JPEG_EXTENSIONS.toSet()

/** JPEGs are compressed; every other image type is copied byte for byte. */
internal fun isJpegFileName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT) in JPEG_EXTENSION_SET

/** The image type implied by a file name's extension, or null when it isn't a known image type. */
internal fun imageMimeTypeForName(name: String): String? =
    MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
        ?.takeIf { it.startsWith("image/") }

internal fun isVideoFileName(name: String): Boolean = videoMimeTypeForName(name) != null

internal fun videoMimeTypeForName(name: String): String? =
    MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
        ?.takeIf { it.startsWith("video/") }

internal fun mediaMimeTypeForName(name: String): String? = imageMimeTypeForName(name) ?: videoMimeTypeForName(name)

internal const val DEFAULT_COPY_BUFFER_BYTES = 16_384

internal const val LOG_TAG = "Slicer"

internal interface RetryableFailure

internal class RetryableCleanupException(message: String) : IOException(message), RetryableFailure

internal enum class ProcessingDecision {
    Compressed,
    CopiedOriginal,
    Failed,
    Cancelled
}

internal enum class SlicerScreen {
    Welcome,
    Setup,
    Quality,
    Compare,
    Processing,
    Results
}

internal data class QualityPreset(
    val label: String,
    val quality: Int
)

internal data class ProcessingProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val compressedCount: Int = 0,
    val copiedCount: Int = 0,
    val failedCount: Int = 0,
    val cancelledCount: Int = 0,
    val currentFile: String? = null,
    val currentFilePercent: Int? = null
)

internal data class BatchFileResult(
    val id: String,
    val inputUri: Uri,
    val inputName: String,
    val outputUri: Uri?,
    val decision: ProcessingDecision,
    val inputBytes: Long,
    val outputBytes: Long,
    val message: String? = null
)

internal val QUALITY_PRESETS = listOf(
    QualityPreset("Small", 55),
    QualityPreset("Balanced", 80),
    QualityPreset("Best", 90)
)
