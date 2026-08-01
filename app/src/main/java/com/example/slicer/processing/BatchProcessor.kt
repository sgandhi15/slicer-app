package com.example.slicer.processing

import com.example.slicer.core.MAX_QUALITY
import com.example.slicer.core.MIN_QUALITY
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.BatchFileResult
import com.example.slicer.storage.createOutputDocument
import com.example.slicer.storage.copyUriContent
import com.example.slicer.storage.resolveContentSize

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun processBatchItem(
    context: Context,
    inputUri: Uri,
    inputName: String,
    outputFolderUri: Uri,
    outputName: String,
    existingOutputUri: Uri?,
    quality: Int,
    inputBytes: Long,
    onOutputCreated: suspend (Uri) -> Unit
): BatchFileResult = withContext(Dispatchers.IO) {
    val outputUri = existingOutputUri?.takeIf { context.canReuseOutputUri(it) }
        ?: context.createOutputDocument(outputFolderUri, outputName)
        ?: return@withContext BatchFileResult(
            inputUri = inputUri,
            inputName = inputName,
            outputUri = null,
            decision = ProcessingDecision.Failed,
            inputBytes = inputBytes,
            outputBytes = -1L,
            message = "Could not create output file in selected folder: $outputName."
        )

    onOutputCreated(outputUri)
    processSingleImage(
        context = context,
        inputUri = inputUri,
        inputName = inputName,
        outputUri = outputUri,
        quality = quality,
        inputBytes = inputBytes
    )
}

private fun Context.canReuseOutputUri(outputUri: Uri): Boolean {
    return runCatching {
        contentResolver.openFileDescriptor(outputUri, "rw")?.use { }
            ?: error("Could not reopen existing output.")
    }.isSuccess
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

    val compressionResult = runCatching {
        context.compressJpegImage(inputUri, outputUri, q)
    }.getOrElse { error ->
        JpegCompressionResult(
            succeeded = false,
            message = error.message ?: "MozJPEG compression failed; original file was preserved."
        )
    }

    if (!compressionResult.succeeded) {
        return fallbackToOriginalOrFail(
            context = context,
            inputUri = inputUri,
            inputName = inputName,
            inputBytes = inputBytes,
            outputUri = outputUri,
            outputLabel = compressionResult.message ?: "MozJPEG compression failed; original file was preserved."
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
