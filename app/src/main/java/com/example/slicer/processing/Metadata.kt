package com.example.slicer.processing

import com.example.slicer.core.LOG_TAG
import com.example.slicer.core.RetryableCleanupException
import com.example.slicer.telemetry.MeasuredInputStream
import com.example.slicer.telemetry.ProcessingMetricsRecorder
import com.example.slicer.telemetry.ProcessingStage

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal suspend fun Context.preserveJpegMetadata(
    sourceUri: Uri,
    encodedFile: File,
    expectedDimensions: JpegDimensions,
    maximumOutputBytes: Long? = null,
    metrics: ProcessingMetricsRecorder = ProcessingMetricsRecorder.Disabled
): Boolean = metrics.measure(ProcessingStage.Metadata) {
    withContext(Dispatchers.IO) {
        val replacement = File(encodedFile.parentFile, "${encodedFile.name}.metadata")
        if (replacement.exists() && !replacement.delete()) return@withContext false
        val coroutineContext = currentCoroutineContext()
        val hardOutputLimit = saturatedAdd(encodedFile.length(), MAX_STREAMED_METADATA_BYTES)
        val outputLimit = minOf(
            maximumOutputBytes?.takeIf { it > 0L } ?: Long.MAX_VALUE,
            hardOutputLimit
        )

        var sourceBytesRead = 0L
        var encodedBytesRead = 0L
        var validationBytesRead = 0L
        var streamedOutputBytes = 0L
        var cancellation: CancellationException? = null
        try {
            var outputBytes = 0L
            val rewriteResult = FileOutputStream(replacement).use { fileOutput ->
                val boundedOutput = BoundedCountingOutputStream(
                    delegate = fileOutput,
                    maximumBytes = outputLimit
                )
                val sourceInput = contentResolver.openInputStream(sourceUri)
                    ?: return@withContext false
                val result = sourceInput.use { source ->
                    val measuredSource = MeasuredInputStream(source)
                    try {
                        encodedFile.inputStream().use { encoded ->
                            val measuredEncoded = MeasuredInputStream(encoded)
                            try {
                                rewriteJpegWithSourceMetadata(
                                    source = measuredSource,
                                    encoded = measuredEncoded,
                                    output = boundedOutput,
                                    expectedDimensions = expectedDimensions,
                                    checkpoint = coroutineContext::ensureActive
                                )
                            } finally {
                                encodedBytesRead = measuredEncoded.bytesRead
                            }
                        }
                    } finally {
                        sourceBytesRead = measuredSource.bytesRead
                        streamedOutputBytes = boundedOutput.bytesWritten
                    }
                }
                if (result is MetadataRewriteResult.Rewritten) {
                    boundedOutput.flush()
                    fileOutput.fd.sync()
                    outputBytes = boundedOutput.bytesWritten
                }
                result
            }

            when (rewriteResult) {
                MetadataRewriteResult.NoMetadata -> return@withContext true
                is MetadataRewriteResult.Invalid -> {
                    Log.w(LOG_TAG, "Metadata streaming failed: ${rewriteResult.reason}")
                    return@withContext false
                }

                is MetadataRewriteResult.Rewritten -> Unit
            }

            if (outputBytes <= 0L || replacement.length() != outputBytes) return@withContext false
            val inspection = replacement.inputStream().use { input ->
                val measured = MeasuredInputStream(input)
                try {
                    inspectCompleteJpeg(
                        input = measured,
                        expectedDimensions = expectedDimensions,
                        checkpoint = coroutineContext::ensureActive
                    )
                } finally {
                    validationBytesRead = measured.bytesRead
                }
            }
            if (inspection is JpegInspectionResult.Invalid) {
                Log.w(LOG_TAG, "Metadata output validation failed: ${inspection.reason}")
                return@withContext false
            }

            coroutineContext.ensureActive()
            Files.move(
                replacement.toPath(),
                encodedFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            encodedFile.isFile && encodedFile.length() == outputBytes
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
            throw cancelled
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Could not preserve JPEG metadata safely.", error)
            false
        } finally {
            metrics.recordIo(
                stage = ProcessingStage.Metadata,
                bytesRead = saturatedAdd(
                    saturatedAdd(sourceBytesRead, encodedBytesRead),
                    validationBytesRead
                ),
                bytesWritten = streamedOutputBytes
            )
            if (replacement.exists() && !replacement.delete()) {
                val cleanupError = RetryableCleanupException(
                    "Could not remove metadata replacement '${replacement.name}'."
                )
                cancellation?.addSuppressed(cleanupError) ?: throw cleanupError
            }
        }
    }
}

private class BoundedCountingOutputStream(
    private val delegate: OutputStream,
    private val maximumBytes: Long
) : OutputStream() {
    var bytesWritten: Long = 0L
        private set

    override fun write(value: Int) {
        requireCapacity(1)
        delegate.write(value)
        bytesWritten++
    }

    override fun write(buffer: ByteArray, offset: Int, byteCount: Int) {
        requireCapacity(byteCount)
        delegate.write(buffer, offset, byteCount)
        bytesWritten += byteCount.toLong()
    }

    override fun flush() = delegate.flush()

    private fun requireCapacity(byteCount: Int) {
        if (byteCount < 0 || bytesWritten > maximumBytes - byteCount.toLong()) {
            throw OutputLimitExceededException()
        }
    }
}

private class OutputLimitExceededException : IOException(
    "Metadata output exceeds the safe streaming limit."
)

private fun saturatedAdd(left: Long, right: Long): Long {
    return if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

private const val MAX_STREAMED_METADATA_BYTES = 32L * 1024L * 1024L
