#include <android/log.h>
#include <jni.h>

#include <climits>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <setjmp.h>
#include <unistd.h>

#if MOZJPEG_ENABLED
#include "jerror.h"
#include "jpeglib.h"
#endif

#if MOZJPEG_ENABLED
namespace {

constexpr int VALIDATION_VALID = 0;
constexpr int VALIDATION_INVALID_DATA = 1;
constexpr int VALIDATION_IO_FAILURE = 2;
constexpr int VALIDATION_RESOURCE_LIMIT = 3;

struct JpegErrorManager {
    jpeg_error_mgr base;
    jmp_buf jumpBuffer;
    char message[JMSG_LENGTH_MAX];
    FILE* input;
    int validationStatus;
};

[[noreturn]] void handleJpegError(j_common_ptr jpegInfo) {
    auto* error = reinterpret_cast<JpegErrorManager*>(jpegInfo->err);
    (*jpegInfo->err->format_message)(jpegInfo, error->message);
    if (jpegInfo->err->msg_code == JERR_OUT_OF_MEMORY ||
        jpegInfo->err->msg_code == JERR_NO_BACKING_STORE) {
        error->validationStatus = VALIDATION_RESOURCE_LIMIT;
    } else if (error->input != nullptr && ferror(error->input) != 0) {
        error->validationStatus = VALIDATION_IO_FAILURE;
    }
    longjmp(error->jumpBuffer, 1);
}

void handleJpegMessage(j_common_ptr jpegInfo, int messageLevel) {
    if (messageLevel >= 0) {
        return;
    }
    auto* error = reinterpret_cast<JpegErrorManager*>(jpegInfo->err);
    (*jpegInfo->err->format_message)(jpegInfo, error->message);
    error->validationStatus = error->input != nullptr && ferror(error->input) != 0
        ? VALIDATION_IO_FAILURE
        : VALIDATION_INVALID_DATA;
    longjmp(error->jumpBuffer, 1);
}

int validateJpeg(
    FILE* input,
    int expectedWidth,
    int expectedHeight,
    long maximumWorkingBytes
) {
    auto* decompressor = static_cast<jpeg_decompress_struct*>(
        calloc(1, sizeof(jpeg_decompress_struct))
    );
    auto* error = static_cast<JpegErrorManager*>(calloc(1, sizeof(JpegErrorManager)));
    if (decompressor == nullptr || error == nullptr) {
        free(decompressor);
        free(error);
        return VALIDATION_RESOURCE_LIMIT;
    }
    error->input = input;
    error->validationStatus = VALIDATION_INVALID_DATA;
    decompressor->err = jpeg_std_error(&error->base);
    error->base.error_exit = handleJpegError;
    error->base.emit_message = handleJpegMessage;

    if (setjmp(error->jumpBuffer) != 0) {
        jpeg_destroy_decompress(decompressor);
        __android_log_print(
            ANDROID_LOG_WARN,
            "SlicerMozJpeg",
            "JPEG validation failed: %s",
            error->message[0] != '\0' ? error->message : "unknown error"
        );
        const int status = error->validationStatus;
        free(decompressor);
        free(error);
        return status;
    }

    jpeg_create_decompress(decompressor);
    decompressor->mem->max_memory_to_use = maximumWorkingBytes;
    jpeg_stdio_src(decompressor, input);
    if (jpeg_read_header(decompressor, TRUE) != JPEG_HEADER_OK ||
        decompressor->image_width != static_cast<JDIMENSION>(expectedWidth) ||
        decompressor->image_height != static_cast<JDIMENSION>(expectedHeight)) {
        jpeg_destroy_decompress(decompressor);
        free(decompressor);
        free(error);
        return VALIDATION_INVALID_DATA;
    }

    if (decompressor->progressive_mode == TRUE) {
        uint64_t coefficientBytes = 8ULL * 1024ULL * 1024ULL;
        for (int index = 0; index < decompressor->num_components; ++index) {
            const auto& component = decompressor->comp_info[index];
            const uint64_t blocks = static_cast<uint64_t>(component.width_in_blocks) *
                static_cast<uint64_t>(component.height_in_blocks);
            constexpr uint64_t bytesPerBlock = DCTSIZE2 * sizeof(JCOEF);
            if (blocks > (UINT64_MAX - coefficientBytes) / bytesPerBlock) {
                jpeg_destroy_decompress(decompressor);
                free(decompressor);
                free(error);
                return VALIDATION_RESOURCE_LIMIT;
            }
            coefficientBytes += blocks * bytesPerBlock;
        }
        if (coefficientBytes > static_cast<uint64_t>(maximumWorkingBytes)) {
            jpeg_destroy_decompress(decompressor);
            free(decompressor);
            free(error);
            return VALIDATION_RESOURCE_LIMIT;
        }
    }

    decompressor->scale_num = 1;
    decompressor->scale_denom = 8;
    if (decompressor->jpeg_color_space == JCS_CMYK ||
        decompressor->jpeg_color_space == JCS_YCCK) {
        decompressor->out_color_space = JCS_CMYK;
    } else if (decompressor->jpeg_color_space == JCS_GRAYSCALE) {
        decompressor->out_color_space = JCS_GRAYSCALE;
    } else {
        decompressor->out_color_space = JCS_RGB;
    }
    decompressor->do_fancy_upsampling = FALSE;
    decompressor->do_block_smoothing = FALSE;
    if (jpeg_start_decompress(decompressor) == FALSE || decompressor->output_components <= 0) {
        jpeg_destroy_decompress(decompressor);
        free(decompressor);
        free(error);
        return VALIDATION_INVALID_DATA;
    }

    const uint64_t rowBytes = static_cast<uint64_t>(decompressor->output_width) *
        static_cast<uint64_t>(decompressor->output_components);
    if (rowBytes == 0 || rowBytes > static_cast<uint64_t>(UINT_MAX)) {
        jpeg_destroy_decompress(decompressor);
        free(decompressor);
        free(error);
        return VALIDATION_RESOURCE_LIMIT;
    }
    JSAMPARRAY row = (*decompressor->mem->alloc_sarray)(
        reinterpret_cast<j_common_ptr>(decompressor),
        JPOOL_IMAGE,
        static_cast<JDIMENSION>(rowBytes),
        1
    );
    while (decompressor->output_scanline < decompressor->output_height) {
        if (jpeg_read_scanlines(decompressor, row, 1) != 1) {
            ERREXIT(decompressor, JERR_CANT_SUSPEND);
        }
    }
    if (jpeg_finish_decompress(decompressor) == FALSE) {
        jpeg_destroy_decompress(decompressor);
        free(decompressor);
        free(error);
        return VALIDATION_INVALID_DATA;
    }
    jpeg_destroy_decompress(decompressor);
    free(decompressor);
    free(error);
    return VALIDATION_VALID;
}

}  // namespace
#endif

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

extern "C" JNIEXPORT jint JNICALL
Java_com_example_slicer_processing_MozJpegNative_validateJpegFileDescriptorNative(
    JNIEnv*,
    jclass,
    jint inputFd,
    jint expectedWidth,
    jint expectedHeight,
    jlong maximumWorkingBytes
) {
#if MOZJPEG_ENABLED
    if (inputFd < 0 || expectedWidth <= 0 || expectedHeight <= 0 || maximumWorkingBytes <= 0) {
        return VALIDATION_IO_FAILURE;
    }

    const int ownedFd = dup(inputFd);
    if (ownedFd < 0) {
        return VALIDATION_IO_FAILURE;
    }
    if (lseek(ownedFd, 0, SEEK_SET) < 0) {
        close(ownedFd);
        return VALIDATION_IO_FAILURE;
    }
    FILE* input = fdopen(ownedFd, "rb");
    if (input == nullptr) {
        close(ownedFd);
        return VALIDATION_IO_FAILURE;
    }

    const long safeWorkingBytes = maximumWorkingBytes > static_cast<jlong>(LONG_MAX)
        ? LONG_MAX
        : static_cast<long>(maximumWorkingBytes);
    const int result = validateJpeg(input, expectedWidth, expectedHeight, safeWorkingBytes);
    const bool closed = fclose(input) == 0;
    return closed ? result : VALIDATION_IO_FAILURE;
#else
    return VALIDATION_IO_FAILURE;
#endif
}
