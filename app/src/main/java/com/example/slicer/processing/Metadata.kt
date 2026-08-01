package com.example.slicer.processing

import com.example.slicer.core.LOG_TAG

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
    maximumOutputBytes: Long? = null
): Boolean = withContext(Dispatchers.IO) {
    val replacement = File(encodedFile.parentFile, "${encodedFile.name}.metadata")
    if (replacement.exists() && !replacement.delete()) return@withContext false
    val coroutineContext = currentCoroutineContext()
    val hardOutputLimit = saturatedAdd(encodedFile.length(), MAX_STREAMED_METADATA_BYTES)
    val outputLimit = minOf(
        maximumOutputBytes?.takeIf { it > 0L } ?: Long.MAX_VALUE,
        hardOutputLimit
    )

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
                encodedFile.inputStream().use { encoded ->
                    rewriteJpegWithSourceMetadata(
                        source = source,
                        encoded = encoded,
                        output = boundedOutput,
                        expectedDimensions = expectedDimensions,
                        checkpoint = coroutineContext::ensureActive
                    )
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
        val inspection = replacement.inspectCompleteJpeg(
            expectedDimensions = expectedDimensions,
            checkpoint = coroutineContext::ensureActive
        )
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
        throw cancelled
    } catch (error: Exception) {
        Log.w(LOG_TAG, "Could not preserve JPEG metadata safely.", error)
        false
    } finally {
        if (replacement.exists() && !replacement.delete()) {
            Log.w(LOG_TAG, "Could not remove metadata replacement '${replacement.name}'.")
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
