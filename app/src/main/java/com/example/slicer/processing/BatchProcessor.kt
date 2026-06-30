package com.example.slicer.processing

import com.example.slicer.core.LOG_TAG
import com.example.slicer.core.MAX_QUALITY
import com.example.slicer.core.MIN_QUALITY
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.BatchFileResult
import com.example.slicer.core.ProcessingProgress
import com.example.slicer.storage.createOutputDocument
import com.example.slicer.storage.copyUriContent
import com.example.slicer.storage.queryExistingOutputNames
import com.example.slicer.storage.resolveContentSize
import com.example.slicer.storage.resolveDisplayName
import com.example.slicer.storage.sanitizeOutputFileName
import com.example.slicer.storage.uniqueOutputName

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    }.getOrDefault(false)

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
