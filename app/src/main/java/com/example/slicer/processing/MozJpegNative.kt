package com.example.slicer.processing

import android.os.ParcelFileDescriptor
import java.io.File
import java.util.function.BooleanSupplier

internal enum class NativeCompressionStatus {
    Success, InvalidData, IoFailure, ResourceLimit, Cancelled, Unsupported, SourceOutputCollision, Unavailable
}

internal data class NativeCompressionResult(
    val status: NativeCompressionStatus,
    val estimatedPeakBytes: Long = 0L,
    val bytesRead: Long = 0L
)

private const val LIB_NAME_MOZJPEG = "slicer_mozjpeg"

internal enum class NativeJpegValidationResult {
    Valid,
    InvalidData,
    IoFailure,
    ResourceLimit,
    Unavailable
}

internal object MozJpegNative {
    private val isLibraryLoaded: Boolean = runCatching {
        System.loadLibrary(LIB_NAME_MOZJPEG)
    }.isSuccess

    private val isNativeEncoderEnabled: Boolean = if (isLibraryLoaded) {
        runCatching { isMozJpegEnabled() }.getOrDefault(false)
    } else {
        false
    }

    val isAvailable: Boolean get() = isLibraryLoaded && isNativeEncoderEnabled
    val isMozJpegFeatureEnabled: Boolean get() = isNativeEncoderEnabled

    fun runtimeStatusMessage(): String = buildString {
        append("MozJPEG runtime status: libraryLoaded=")
        append(isLibraryLoaded)
        append(", isMozJpegEnabled=")
        append(isNativeEncoderEnabled)
        append(", isAvailable=")
        append(isAvailable)
    }

    @JvmStatic
    private external fun isMozJpegEnabled(): Boolean

    fun transcodeJpeg(
        inputFileDescriptor: Int,
        outputFileDescriptor: Int,
        dimensions: JpegDimensions,
        quality: Int,
        memoryBudgetBytes: Long,
        cancelled: BooleanSupplier = BooleanSupplier { false },
        inputOffset: Long = 0L,
        inputLength: Long = -1L
    ): NativeCompressionResult {
        if (!isAvailable) return NativeCompressionResult(NativeCompressionStatus.Unavailable)
        val result = transcodeJpegNative(
            inputFileDescriptor, outputFileDescriptor, dimensions.width, dimensions.height,
            quality.coerceIn(1, 100), memoryBudgetBytes, cancelled, inputOffset, inputLength
        )
        return NativeCompressionResult(
            status = NativeCompressionStatus.entries.getOrNull(result[0].toInt())
                ?: NativeCompressionStatus.Unavailable,
            estimatedPeakBytes = result[1],
            bytesRead = result[2]
        )
    }

    @JvmStatic
    private external fun transcodeJpegNative(
        inputFileDescriptor: Int,
        outputFileDescriptor: Int,
        width: Int,
        height: Int,
        quality: Int,
        memoryBudgetBytes: Long,
        cancelled: BooleanSupplier,
        inputOffset: Long,
        inputLength: Long
    ): LongArray

    fun validateJpegFileDescriptor(
        inputFileDescriptor: Int,
        expectedDimensions: JpegDimensions,
        maximumWorkingBytes: Long
    ): NativeJpegValidationResult {
        if (!isAvailable || inputFileDescriptor < 0 ||
            expectedDimensions.width <= 0 || expectedDimensions.height <= 0 ||
            maximumWorkingBytes <= 0L
        ) {
            return NativeJpegValidationResult.Unavailable
        }

        return when (
            validateJpegFileDescriptorNative(
                inputFileDescriptor = inputFileDescriptor,
                expectedWidth = expectedDimensions.width,
                expectedHeight = expectedDimensions.height,
                maximumWorkingBytes = maximumWorkingBytes
            )
        ) {
            NATIVE_VALIDATION_VALID -> NativeJpegValidationResult.Valid
            NATIVE_VALIDATION_INVALID_DATA -> NativeJpegValidationResult.InvalidData
            NATIVE_VALIDATION_IO_FAILURE -> NativeJpegValidationResult.IoFailure
            NATIVE_VALIDATION_RESOURCE_LIMIT -> NativeJpegValidationResult.ResourceLimit
            else -> NativeJpegValidationResult.Unavailable
        }
    }

    @JvmStatic
    private external fun validateJpegFileDescriptorNative(
        inputFileDescriptor: Int,
        expectedWidth: Int,
        expectedHeight: Int,
        maximumWorkingBytes: Long
    ): Int

    private const val NATIVE_VALIDATION_VALID = 0
    private const val NATIVE_VALIDATION_INVALID_DATA = 1
    private const val NATIVE_VALIDATION_IO_FAILURE = 2
    private const val NATIVE_VALIDATION_RESOURCE_LIMIT = 3
}

internal fun File.validateJpegViaMozJpeg(
    expectedDimensions: JpegDimensions,
    maximumWorkingBytes: Long
): NativeJpegValidationResult {
    if (!isFile || length() <= 0L) return NativeJpegValidationResult.InvalidData
    return runCatching {
        ParcelFileDescriptor.open(this, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            MozJpegNative.validateJpegFileDescriptor(
                inputFileDescriptor = descriptor.fd,
                expectedDimensions = expectedDimensions,
                maximumWorkingBytes = maximumWorkingBytes
            )
        }
    }.getOrDefault(NativeJpegValidationResult.IoFailure)
}
