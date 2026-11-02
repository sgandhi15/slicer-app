@file:OptIn(UnstableApi::class)

package com.example.slicer.processing

import com.example.slicer.core.LOG_TAG
import com.example.slicer.core.ORIGINAL_RESOLUTION
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.RetryableFailure
import com.example.slicer.core.VideoQuality
import com.example.slicer.telemetry.ProcessingMetricsRecorder

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4TimestampData
import androidx.media3.effect.Presentation
import androidx.media3.inspector.MetadataRetriever
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal data class VideoSettings(val quality: VideoQuality, val shortSide: Int)

/** Thrown when the user cancels the batch while a video is still encoding. */
internal class VideoCancelledException : IOException("Cancelled by user."), RetryableFailure

private class VideoSourceReadException(message: String, cause: Throwable) : IOException(message, cause), RetryableFailure

private data class SourceVideo(
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val isHdr: Boolean,
    val durationUs: Long
) {
    val shortSide: Int get() = minOf(width, height)
}

private data class ExportAttempt(val videoMimeType: String, val hdrMode: Int)

private sealed interface ExportOutcome {
    data class Finished(val width: Int, val height: Int) : ExportOutcome
    data class Failed(val error: ExportException) : ExportOutcome
}

/**
 * Re-encodes a video to MP4 with the phone's hardware encoder. HEVC is tried first; if the phone
 * can't handle this video that way, H.264 (tone-mapped to SDR when needed) is tried next. When
 * neither works, or the result isn't smaller, the original is copied unchanged.
 */
internal suspend fun Context.compressVideo(
    inputUri: Uri,
    outputFile: File,
    inputBytes: Long,
    settings: VideoSettings,
    metrics: ProcessingMetricsRecorder,
    onProgress: suspend (Int) -> Unit,
    isCancelled: suspend () -> Boolean
): PreparedBatchOutput {
    requireFreeSpaceFor(inputBytes)
    val source = probeVideo(inputUri)
        ?: return copyUnchanged(this, inputUri, outputFile, inputBytes, metrics, UNSUPPORTED_MESSAGE)
    val captureDate = bestDateFor(inputUri)

    val expectedShortSide = if (settings.shortSide != ORIGINAL_RESOLUTION) {
        minOf(source.shortSide, settings.shortSide)
    } else {
        source.shortSide
    }
    for (attempt in exportAttempts(source)) {
        outputFile.delete()
        when (val outcome = export(inputUri, outputFile, attempt, source, settings, inputBytes, captureDate, onProgress, isCancelled)) {
            is ExportOutcome.Failed -> {
                Log.w(LOG_TAG, "Video export with ${attempt.videoMimeType} failed: ${outcome.error.errorCodeName}", outcome.error)
                outcome.error.throwIfSourceUnreadable()
                continue
            }
            is ExportOutcome.Finished -> {
                // An encoder that can't handle the size makes Media3 quietly shrink the video; never accept that.
                val shortSide = minOf(outcome.width, outcome.height)
                if (shortSide > 0 && shortSide < expectedShortSide * MIN_ACCEPTED_SCALE) {
                    Log.w(LOG_TAG, "Video export with ${attempt.videoMimeType} fell back to ${outcome.width}x${outcome.height}.")
                    continue
                }
            }
        }
        val outputBytes = outputFile.length()
        if (outputBytes <= 0L) continue
        if (inputBytes in 1..outputBytes) {
            return copyUnchanged(this, inputUri, outputFile, inputBytes, metrics, NOT_SMALLER_MESSAGE)
        }
        return PreparedBatchOutput(ProcessingDecision.Compressed, outputBytes, message = null)
    }
    return copyUnchanged(this, inputUri, outputFile, inputBytes, metrics, CONVERSION_FAILED_MESSAGE)
}

private suspend fun Context.export(
    inputUri: Uri,
    outputFile: File,
    attempt: ExportAttempt,
    source: SourceVideo,
    settings: VideoSettings,
    inputBytes: Long,
    captureDate: Date?,
    onProgress: suspend (Int) -> Unit,
    isCancelled: suspend () -> Boolean
): ExportOutcome = withContext(Dispatchers.Main) {
    val scale = if (settings.shortSide != ORIGINAL_RESOLUTION && source.shortSide > settings.shortSide) {
        settings.shortSide.toDouble() / source.shortSide
    } else {
        1.0
    }
    val bitrate = targetVideoBitrate(
        pixels = (source.width * scale).roundToInt().toLong() * (source.height * scale).roundToInt(),
        frameRate = source.frameRate,
        quality = settings.quality,
        isHevc = attempt.videoMimeType == MimeTypes.VIDEO_H265,
        sourceBitrate = sourceBitrate(inputBytes, source.durationUs)
    )

    val finished = CompletableDeferred<ExportOutcome>()
    val transformer = Transformer.Builder(this@export)
        .setVideoMimeType(attempt.videoMimeType)
        .setEncoderFactory(
            DefaultEncoderFactory.Builder(this@export)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                .build()
        )
        .setMuxerFactory(InAppMp4Muxer.Factory { entries -> entries.addCaptureDateIfMissing(captureDate) })
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                finished.complete(ExportOutcome.Finished(exportResult.width, exportResult.height))
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                finished.complete(ExportOutcome.Failed(exportException))
            }
        })
        .build()

    val effects = if (scale < 1.0) {
        Effects(emptyList(), listOf(Presentation.createForShortSide(settings.shortSide)))
    } else {
        Effects.EMPTY
    }
    val item = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
        .setEffects(effects)
        // Samsung slow-motion clips only play slowly in the Samsung gallery; flattening keeps the effect.
        .setFlattenForSlowMotion(true)
        .build()
    val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
        .setHdrMode(attempt.hdrMode)
        .build()

    transformer.start(composition, outputFile.absolutePath)
    try {
        val progress = ProgressHolder()
        var reportedPercent = -1
        while (!finished.isCompleted) {
            withTimeoutOrNull(PROGRESS_POLL_MILLIS) { finished.join() }
            if (finished.isCompleted) break
            if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE &&
                progress.progress != reportedPercent
            ) {
                reportedPercent = progress.progress
                onProgress(reportedPercent)
            }
            if (isCancelled()) throw VideoCancelledException()
        }
        finished.await()
    } finally {
        if (!finished.isCompleted) transformer.cancel()
    }
}

private suspend fun Context.probeVideo(uri: Uri): SourceVideo? = withContext(Dispatchers.IO) {
    val retriever = MetadataRetriever.Builder(this@probeVideo, MediaItem.fromUri(uri)).build()
    try {
        val trackGroups = retriever.retrieveTrackGroups().get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val video = (0 until trackGroups.length)
            .map { index -> trackGroups[index].getFormat(0) }
            .firstOrNull { format -> MimeTypes.isVideo(format.sampleMimeType) && format.width > 0 && format.height > 0 }
            ?: return@withContext null
        val durationUs = runCatching { retriever.retrieveDurationUs().get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .getOrDefault(C.TIME_UNSET)
        SourceVideo(
            width = video.width,
            height = video.height,
            frameRate = video.frameRate,
            isHdr = ColorInfo.isTransferHdr(video.colorInfo),
            durationUs = durationUs
        )
    } catch (error: Exception) {
        Log.w(LOG_TAG, "Could not read video tracks.", error)
        null
    } finally {
        retriever.close()
    }
}

private fun exportAttempts(source: SourceVideo): List<ExportAttempt> = buildList {
    if (hasEncoderFor(MimeTypes.VIDEO_H265)) {
        add(ExportAttempt(MimeTypes.VIDEO_H265, Composition.HDR_MODE_KEEP_HDR))
    }
    val h264HdrMode = if (source.isHdr) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR
    add(ExportAttempt(MimeTypes.VIDEO_H264, h264HdrMode))
}

private fun hasEncoderFor(mimeType: String): Boolean =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
    }

internal fun targetVideoBitrate(
    pixels: Long,
    frameRate: Float,
    quality: VideoQuality,
    isHevc: Boolean,
    sourceBitrate: Long?
): Int {
    // Above 60 fps, extra frames are near-duplicates and barely need more bits.
    val fps = if (frameRate == Format.NO_VALUE.toFloat() || frameRate <= 0f) 30f else frameRate.coerceIn(1f, 60f)
    val bitsPerPixel = quality.bitsPerPixel * if (isHevc) 1.0 else H264_BITRATE_FACTOR
    var target = pixels * fps * bitsPerPixel
    if (sourceBitrate != null && sourceBitrate > 0L) target = minOf(target, sourceBitrate * SOURCE_BITRATE_CAP)
    return target.toLong().coerceIn(MIN_VIDEO_BITRATE, Int.MAX_VALUE.toLong()).toInt()
}

private fun sourceBitrate(inputBytes: Long, durationUs: Long): Long? {
    if (inputBytes <= 0L || durationUs == C.TIME_UNSET || durationUs <= 0L) return null
    return inputBytes * 8L * 1_000_000L / durationUs
}

/** Videos from messaging apps often lack a recording date; without one the gallery files them under today. */
private fun MutableSet<Metadata.Entry>.addCaptureDateIfMissing(captureDate: Date?) {
    if (captureDate == null) return
    val existing = filterIsInstance<Mp4TimestampData>()
    if (existing.any { it.creationTimestampSeconds > MP4_UNIX_EPOCH_SECONDS }) return
    removeAll(existing.toSet())
    val seconds = Mp4TimestampData.unixTimeToMp4TimeSeconds(captureDate.time)
    add(Mp4TimestampData(seconds, seconds))
}

private fun ExportException.throwIfSourceUnreadable() {
    when (errorCode) {
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        ExportException.ERROR_CODE_IO_NO_PERMISSION -> throw IOException("The video could no longer be read.", this)
        in IO_ERROR_CODES -> throw VideoSourceReadException("The video could not be read.", this)
    }
}

private fun Context.requireFreeSpaceFor(inputBytes: Long) {
    if (inputBytes > 0L && noBackupFilesDir.usableSpace < inputBytes + FREE_SPACE_MARGIN_BYTES) {
        throw IOException("Not enough free storage to compress this video.")
    }
}

private const val UNSUPPORTED_MESSAGE = "This video format can't be converted; the original was copied."
private const val CONVERSION_FAILED_MESSAGE = "This video couldn't be converted on this phone; the original was copied."
private const val NOT_SMALLER_MESSAGE = "The compressed video wasn't smaller; the original was copied."

private const val PROGRESS_POLL_MILLIS = 500L
private const val PROBE_TIMEOUT_SECONDS = 30L
private const val MIN_VIDEO_BITRATE = 300_000L
private const val H264_BITRATE_FACTOR = 1.5
private const val SOURCE_BITRATE_CAP = 0.8
private const val MIN_ACCEPTED_SCALE = 0.9
private const val FREE_SPACE_MARGIN_BYTES = 200L * 1024 * 1024
private val IO_ERROR_CODES = 2000..2999
private val MP4_UNIX_EPOCH_SECONDS = Mp4TimestampData.unixTimeToMp4TimeSeconds(0L)
