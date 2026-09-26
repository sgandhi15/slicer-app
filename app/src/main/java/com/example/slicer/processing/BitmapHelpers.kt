package com.example.slicer.processing

import android.graphics.ImageDecoder
import java.io.File
import java.io.IOException

internal enum class PlatformJpegValidationResult {
    Valid,
    InvalidData,
    IoFailure,
    ResourceLimit
}

internal fun File.validateJpegWithPlatformDecoder(
    expectedDimensions: JpegDimensions
): PlatformJpegValidationResult {
    if (!isFile || length() <= 0L) return PlatformJpegValidationResult.InvalidData
    return try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(this)) { decoder, info, _ ->
            check(
                info.size.width == expectedDimensions.width &&
                    info.size.height == expectedDimensions.height
            ) { "JPEG dimensions changed during validation." }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(
                sampleSizeForComparison(expectedDimensions.width, expectedDimensions.height)
            )
        }
        bitmap.recycle()
        PlatformJpegValidationResult.Valid
    } catch (_: OutOfMemoryError) {
        PlatformJpegValidationResult.ResourceLimit
    } catch (error: ImageDecoder.DecodeException) {
        if (error.error == ImageDecoder.DecodeException.SOURCE_EXCEPTION) {
            PlatformJpegValidationResult.IoFailure
        } else {
            PlatformJpegValidationResult.InvalidData
        }
    } catch (_: IOException) {
        PlatformJpegValidationResult.IoFailure
    } catch (_: Exception) {
        PlatformJpegValidationResult.InvalidData
    }
}

private fun sampleSizeForComparison(width: Int, height: Int): Int {
    var sampleSize = 1
    while (maxOf(width, height) / sampleSize > MAX_COMPARISON_DIMENSION) sampleSize *= 2
    return sampleSize
}

private const val MAX_COMPARISON_DIMENSION = 128
