package com.example.slicer.processing

import android.graphics.Bitmap

private const val TJ_SAMPLING_420 = 2
private const val TJ_SAMPLING_422 = 1
private const val TJ_SAMPLING_444 = 0

private const val LIB_NAME_MOZJPEG = "slicer_mozjpeg"

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

    fun compressBitmapToJpeg(
        bitmap: Bitmap,
        outputFileDescriptor: Int,
        quality: Int,
        progressive: Boolean = true,
        subsampling: Int = TJ_SAMPLING_420,
        accurateDct: Boolean = true
    ): Boolean {
        if (!isAvailable || bitmap.isRecycled || outputFileDescriptor < 0) return false
        if (bitmap.width <= 0 || bitmap.height <= 0) return false

        val safeQuality = quality.coerceIn(1, 100)
        val safeSubsampling = when (subsampling) {
            TJ_SAMPLING_444, TJ_SAMPLING_422, TJ_SAMPLING_420 -> subsampling
            else -> TJ_SAMPLING_420
        }

        return compressBitmapToFileDescriptorNative(
            bitmap = bitmap,
            outputFileDescriptor = outputFileDescriptor,
            quality = safeQuality,
            subsampling = safeSubsampling,
            progressive = progressive,
            accurateDct = accurateDct
        )
    }

    @JvmStatic
    private external fun compressBitmapToFileDescriptorNative(
        bitmap: Bitmap,
        outputFileDescriptor: Int,
        quality: Int,
        subsampling: Int,
        progressive: Boolean,
        accurateDct: Boolean
    ): Boolean
}
