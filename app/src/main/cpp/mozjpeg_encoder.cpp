#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <setjmp.h>
#include <unistd.h>

#if MOZJPEG_ENABLED
#include "jerror.h"
#include "jpeglib.h"
#endif

#if MOZJPEG_ENABLED
namespace {

constexpr int SUBSAMPLING_444 = 0;
constexpr int SUBSAMPLING_422 = 1;
constexpr int SUBSAMPLING_420 = 2;

struct JpegErrorManager {
    jpeg_error_mgr base;
    jmp_buf jumpBuffer;
    char message[JMSG_LENGTH_MAX];
};

[[noreturn]] void handleJpegError(j_common_ptr jpegInfo) {
    auto* error = reinterpret_cast<JpegErrorManager*>(jpegInfo->err);
    (*jpegInfo->err->format_message)(jpegInfo, error->message);
    longjmp(error->jumpBuffer, 1);
}

bool configureSubsampling(j_compress_ptr compressor, int subsampling) {
    if (compressor->num_components < 3) {
        return false;
    }

    switch (subsampling) {
        case SUBSAMPLING_444:
            compressor->comp_info[0].h_samp_factor = 1;
            compressor->comp_info[0].v_samp_factor = 1;
            break;
        case SUBSAMPLING_422:
            compressor->comp_info[0].h_samp_factor = 2;
            compressor->comp_info[0].v_samp_factor = 1;
            break;
        case SUBSAMPLING_420:
            compressor->comp_info[0].h_samp_factor = 2;
            compressor->comp_info[0].v_samp_factor = 2;
            break;
        default:
            return false;
    }

    compressor->comp_info[1].h_samp_factor = 1;
    compressor->comp_info[1].v_samp_factor = 1;
    compressor->comp_info[2].h_samp_factor = 1;
    compressor->comp_info[2].v_samp_factor = 1;
    return true;
}

bool encodeLockedBitmap(
    FILE* output,
    const AndroidBitmapInfo& bitmapInfo,
    void* pixels,
    int quality,
    int subsampling,
    bool progressive,
    bool accurateDct
) {
    jpeg_compress_struct compressor{};
    JpegErrorManager error{};
    compressor.err = jpeg_std_error(&error.base);
    error.base.error_exit = handleJpegError;

    if (setjmp(error.jumpBuffer) != 0) {
        jpeg_destroy_compress(&compressor);
        __android_log_print(
            ANDROID_LOG_WARN,
            "SlicerMozJpeg",
            "MozJPEG compression failed: %s",
            error.message[0] != '\0' ? error.message : "unknown error"
        );
        return false;
    }

    jpeg_create_compress(&compressor);
    jpeg_stdio_dest(&compressor, output);

    compressor.image_width = bitmapInfo.width;
    compressor.image_height = bitmapInfo.height;
    compressor.input_components = 4;
    compressor.in_color_space = JCS_EXT_RGBA;
    jpeg_set_defaults(&compressor);
    jpeg_set_quality(&compressor, quality, TRUE);
    compressor.dct_method = accurateDct ? JDCT_ISLOW : JDCT_IFAST;
    compressor.optimize_coding = TRUE;

    if (!configureSubsampling(&compressor, subsampling)) {
        jpeg_destroy_compress(&compressor);
        return false;
    }

    if (progressive) {
        jpeg_c_set_bool_param(&compressor, JBOOLEAN_OPTIMIZE_SCANS, TRUE);
        jpeg_simple_progression(&compressor);
    } else {
        jpeg_c_set_bool_param(&compressor, JBOOLEAN_OPTIMIZE_SCANS, FALSE);
        compressor.scan_info = nullptr;
        compressor.num_scans = 0;
    }

    jpeg_start_compress(&compressor, TRUE);
    auto* pixelBytes = static_cast<unsigned char*>(pixels);
    while (compressor.next_scanline < compressor.image_height) {
        JSAMPROW row = pixelBytes +
            static_cast<size_t>(compressor.next_scanline) * bitmapInfo.stride;
        if (jpeg_write_scanlines(&compressor, &row, 1) != 1) {
            ERREXIT(&compressor, JERR_CANT_SUSPEND);
        }
    }

    jpeg_finish_compress(&compressor);
    jpeg_destroy_compress(&compressor);
    return true;
}

}  // namespace
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_slicer_processing_MozJpegNative_compressBitmapToFileDescriptorNative(
    JNIEnv* env,
    jclass,
    jobject bitmap,
    jint outputFd,
    jint quality,
    jint subsampling,
    jboolean progressive,
    jboolean accurateDct
) {
#if MOZJPEG_ENABLED
    if (bitmap == nullptr || outputFd < 0 || quality < 1 || quality > 100) {
        return JNI_FALSE;
    }

    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmapInfo.width == 0 || bitmapInfo.height == 0 ||
        bitmapInfo.width > JPEG_MAX_DIMENSION || bitmapInfo.height > JPEG_MAX_DIMENSION ||
        bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        (bitmapInfo.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) != 0 ||
        bitmapInfo.stride < static_cast<uint64_t>(bitmapInfo.width) * 4U) {
        return JNI_FALSE;
    }

    const int ownedFd = dup(outputFd);
    if (ownedFd < 0) {
        return JNI_FALSE;
    }
    if (ftruncate(ownedFd, 0) != 0 || lseek(ownedFd, 0, SEEK_SET) < 0) {
        close(ownedFd);
        return JNI_FALSE;
    }

    FILE* output = fdopen(ownedFd, "wb");
    if (output == nullptr) {
        close(ownedFd);
        return JNI_FALSE;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        pixels == nullptr) {
        fclose(output);
        return JNI_FALSE;
    }

    const bool encoded = encodeLockedBitmap(
        output,
        bitmapInfo,
        pixels,
        quality,
        subsampling,
        progressive == JNI_TRUE,
        accurateDct == JNI_TRUE
    );
    const bool unlocked = AndroidBitmap_unlockPixels(env, bitmap) == ANDROID_BITMAP_RESULT_SUCCESS;
    const bool closed = fclose(output) == 0;

    return encoded && unlocked && closed ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_slicer_processing_MozJpegNative_isMozJpegEnabled(
    JNIEnv*,
    jclass
) {
#if MOZJPEG_ENABLED
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}
