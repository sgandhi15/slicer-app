package com.example.slicer.processing

import com.example.slicer.core.FULL_SIZE
import com.example.slicer.telemetry.MeasuredInputStream
import com.example.slicer.telemetry.ProcessingMetricsRecorder
import com.example.slicer.telemetry.ProcessingStage
import com.example.slicer.telemetry.logMozJpegEventTelemetry

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import java.util.function.BooleanSupplier

internal data class JpegCompressionResult(
    val succeeded: Boolean,
    val message: String? = null,
    val dimensions: JpegDimensions? = null,
    val outputDimensions: JpegDimensions? = dimensions
)

internal class ResizePlan(val scaleNumerator: Int, val output: JpegDimensions)

// libjpeg can only shrink by n/8 while decoding, so decode to the nearest step above the limit
// and let the native resample reach the exact size.
internal fun resizePlan(dimensions: JpegDimensions, maxDimension: Int): ResizePlan {
    val longest = maxOf(dimensions.width, dimensions.height)
    if (maxDimension == FULL_SIZE || longest <= maxDimension) return ResizePlan(8, dimensions)
    val numerator = (1..8).first { (longest * it + 7) / 8 >= maxDimension }
    val decoded = dimensions.scaledBy(numerator)
    val ratio = maxDimension.toDouble() / maxOf(decoded.width, decoded.height)
    val output = JpegDimensions(
        width = (decoded.width * ratio).roundToInt().coerceIn(1, decoded.width),
        height = (decoded.height * ratio).roundToInt().coerceIn(1, decoded.height)
    )
    return ResizePlan(numerator, output)
}

// Same rounding libjpeg uses for its output size.
internal fun JpegDimensions.scaledBy(numerator: Int) = JpegDimensions(
    width = (width * numerator + 7) / 8,
    height = (height * numerator + 7) / 8
)

internal class JpegStatePersistenceException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal class JpegSourceSafetyException : Exception(
    "Source and output refer to the same file; the original was left unchanged."
)

internal suspend fun Context.compressJpegImage(
    inputUri: Uri,
    outputFile: File,
    quality: Int,
    knownDimensions: JpegDimensions? = null,
    onDimensionsRead: suspend (JpegDimensions) -> Unit = {},
    metrics: ProcessingMetricsRecorder = ProcessingMetricsRecorder.Disabled,
    maxDimension: Int = FULL_SIZE,
    budgetShares: Int = 1
): JpegCompressionResult = withContext(Dispatchers.IO) {
    encoderPermits.withPermit {
        runCatching {
            val coroutineContext = currentCoroutineContext()
            var headerBytesRead = 0L
            val headerInspection = try {
                metrics.measure(ProcessingStage.HeaderRead) {
                    contentResolver.openInputStream(inputUri)?.use { input ->
                        val measured = MeasuredInputStream(input)
                        try {
                            inspectJpegHeader(measured, checkpoint = coroutineContext::ensureActive)
                        } finally {
                            headerBytesRead = measured.bytesRead
                        }
                    }
                }
            } finally {
                metrics.recordIo(ProcessingStage.HeaderRead, bytesRead = headerBytesRead)
            }
            headerInspection
                ?: return@runCatching JpegCompressionResult(
                    succeeded = false,
                    message = "Could not read JPEG dimensions; original file was preserved."
                )
            val dimensions = when (headerInspection) {
                is JpegInspectionResult.Valid -> headerInspection.structure.dimensions
                is JpegInspectionResult.Invalid -> {
                    return@runCatching JpegCompressionResult(
                        succeeded = false,
                        message = "JPEG structure was invalid; original file was preserved."
                    )
                }
            }
            if (knownDimensions != null && dimensions != knownDimensions) {
                return@runCatching JpegCompressionResult(
                    succeeded = false,
                    message = "JPEG dimensions changed since this batch was prepared; original file was not reused.",
                    dimensions = knownDimensions
                )
            }
            if (knownDimensions == null) onDimensionsRead(dimensions)

            val resize = resizePlan(dimensions, maxDimension)
            if (resize.output == dimensions && isAlreadyCompressedBelow(inputUri, quality)) {
                return@runCatching JpegCompressionResult(
                    succeeded = false,
                    message = "Already compressed more than this quality would.",
                    dimensions = dimensions
                )
            }
            val budget = metrics.measure(ProcessingStage.MemoryAdmission) {
                nativeEncodingBudget().let { it.copy(bytes = it.bytes / budgetShares.coerceAtLeast(1)) }
            }
            if (budget.rejection != null) {
                metrics.recordMemoryDecision(0L, 0L, budget.rejection.name)
                return@runCatching JpegCompressionResult(
                    false, "Not enough memory is available for optimized JPEG compression; original file was preserved.",
                    dimensions
                )
            }
            val result = metrics.measure(ProcessingStage.NativeEncode) {
                contentResolver.openAssetFileDescriptor(inputUri, "r")?.use { input ->
                    ParcelFileDescriptor.open(outputFile, OUTPUT_FILE_MODE).use { output ->
                        MozJpegNative.transcodeJpeg(
                            inputFileDescriptor = input.parcelFileDescriptor.fd,
                            outputFileDescriptor = output.fd,
                            dimensions = dimensions,
                            quality = quality,
                            memoryBudgetBytes = budget.bytes,
                            cancelled = BooleanSupplier { !coroutineContext.isActive },
                            inputOffset = input.startOffset,
                            inputLength = input.declaredLength,
                            scaleNumerator = resize.scaleNumerator,
                            outputDimensions = resize.output.takeIf { it != dimensions.scaledBy(resize.scaleNumerator) }
                        )
                    }
                } ?: NativeCompressionResult(NativeCompressionStatus.IoFailure)
            }
            metrics.recordIo(ProcessingStage.NativeEncode,
                bytesRead = result.bytesRead, bytesWritten = outputFile.length())
            metrics.recordMemoryDecision(
                result.estimatedPeakBytes, 0L,
                if (result.status == NativeCompressionStatus.ResourceLimit) "NativeResourceLimit" else null
            )
            coroutineContext.ensureActive()
            if (result.status == NativeCompressionStatus.Cancelled) throw CancellationException()
            // An original-copy fallback would write into the source too. Fail the
            // operation instead of treating this as an ordinary compression failure.
            if (result.status == NativeCompressionStatus.SourceOutputCollision) throw JpegSourceSafetyException()
            if (result.status != NativeCompressionStatus.Success) {
                logMozJpegEventTelemetry("MozJPEG transcode failed: ${result.status}")
                return@runCatching JpegCompressionResult(false, result.status.failureMessage(), dimensions)
            }
            // Metadata rewriting and final structural validation happen after both native
            // codecs have been destroyed; no bitmap or codec buffers remain resident.
            JpegCompressionResult(
                succeeded = true,
                dimensions = dimensions,
                outputDimensions = resize.output
            )
        }.getOrElse { error ->
            if (error is CancellationException || error is JpegStatePersistenceException ||
                error is JpegSourceSafetyException
            ) throw error
            JpegCompressionResult(
                succeeded = false,
                message = if (error is OutOfMemoryError) {
                    "The image exceeded the available memory while compressing; original file was preserved."
                } else {
                    error.message ?: "MozJPEG compression failed; original file was preserved."
                }
            )
        }
    }
}

// One spare permit for an inline retry while look-ahead encoders are busy.
private val encoderPermits = Semaphore(MAX_PARALLEL_ENCODERS + 1)

// Re-encoding a photo at a higher quality than it was saved at only makes it bigger.
private fun Context.isAlreadyCompressedBelow(uri: Uri, quality: Int): Boolean {
    val sourceQuality = runCatching {
        contentResolver.openInputStream(uri)?.use(::estimateJpegQuality)
    }.getOrNull() ?: return false
    return sourceQuality <= quality - ALREADY_COMPRESSED_QUALITY_MARGIN
}

internal const val ALREADY_COMPRESSED_QUALITY_MARGIN = 10

private fun NativeCompressionStatus.failureMessage(): String = when (this) {
    NativeCompressionStatus.ResourceLimit ->
        "Not enough memory is available for optimized JPEG compression; original file was preserved."
    NativeCompressionStatus.InvalidData ->
        "JPEG data was invalid or changed while being read; original file was preserved."
    NativeCompressionStatus.IoFailure ->
        "JPEG input or output could not be read or written; original file was preserved."
    NativeCompressionStatus.Unsupported ->
        "This JPEG color space, precision, or dimensions are not supported; original file was preserved."
    NativeCompressionStatus.Unavailable ->
        "MozJPEG is unavailable; original file was preserved."
    else -> "MozJPEG compression did not complete; original file was preserved."
}

// Native code verifies that the descriptors refer to different files before truncating.
private const val OUTPUT_FILE_MODE = ParcelFileDescriptor.MODE_WRITE_ONLY or
    ParcelFileDescriptor.MODE_CREATE
