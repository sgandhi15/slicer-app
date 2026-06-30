package com.example.slicer.processing

private const val TJ_SAMPLING_420 = 2
private const val TJ_SAMPLING_422 = 1
private const val TJ_SAMPLING_444 = 0
private const val TJ_FLAG_PROGRESSIVE = 16384
private const val TJ_FLAG_ACCURATEDCT = 4096
private const val TJ_FLAG_FAST_DCT = 2048

private const val LIB_NAME_MOZJPEG = "slicer_mozjpeg"

object MozJpegNative {
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

    fun compressRgbToJpeg(
        rgbPixels: ByteArray,
        width: Int,
        height: Int,
        quality: Int,
        progressive: Boolean = true,
        subsampling: Int = TJ_SAMPLING_420,
        accurateDct: Boolean = true
    ): ByteArray? {
        if (!isAvailable) return null
        if (rgbPixels.isEmpty() || width <= 0 || height <= 0) return null

        val safeQuality = quality.coerceIn(1, 100)
        val safeSubsampling = when (subsampling) {
            TJ_SAMPLING_444, TJ_SAMPLING_422, TJ_SAMPLING_420 -> subsampling
            else -> TJ_SAMPLING_420
        }

        val flags = (if (progressive) TJ_FLAG_PROGRESSIVE else 0) or
            if (accurateDct) TJ_FLAG_ACCURATEDCT else TJ_FLAG_FAST_DCT

        return compressJpeg(
            rgbPixels = rgbPixels,
            width = width,
            height = height,
            quality = safeQuality,
            subsampling = safeSubsampling,
            flags = flags
        )
    }

    @JvmStatic
    private external fun compressJpeg(
        rgbPixels: ByteArray,
        width: Int,
        height: Int,
        quality: Int,
        subsampling: Int,
        flags: Int
    ): ByteArray?
}
