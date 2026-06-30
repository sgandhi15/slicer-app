#include <jni.h>
#include <android/log.h>

#if MOZJPEG_ENABLED
#include "turbojpeg.h"
#endif

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_slicer_processing_MozJpegNative_compressJpeg(
    JNIEnv* env,
    jclass,
    jbyteArray pixelData,
    jint width,
    jint height,
    jint quality,
    jint subsampling,
    jint flags
) {
#if MOZJPEG_ENABLED
    if (width <= 0 || height <= 0 || quality < 1 || quality > 100 || pixelData == nullptr) {
        return nullptr;
    }

    const jsize sourceSize = env->GetArrayLength(pixelData);
    const long long expectedSize = 1LL * width * height * 3;
    if (expectedSize <= 0 || expectedSize > 0x7FFFFFFFLL || sourceSize < static_cast<jsize>(expectedSize)) {
        return nullptr;
    }

    jbyte* pixels = env->GetByteArrayElements(pixelData, nullptr);
    if (pixels == nullptr) {
        return nullptr;
    }

    const tjhandle compressor = tjInitCompress();
    if (compressor == nullptr) {
        env->ReleaseByteArrayElements(pixelData, pixels, JNI_ABORT);
        return nullptr;
    }

    unsigned char* jpegBuffer = nullptr;
    unsigned long jpegSize = 0;
    const int pitch = static_cast<int>(width) * 3;
    const int rc = tjCompress2(
        compressor,
        reinterpret_cast<unsigned char*>(pixels),
        width,
        pitch,
        height,
        TJPF_RGB,
        &jpegBuffer,
        &jpegSize,
        subsampling,
        quality,
        flags
    );

    env->ReleaseByteArrayElements(pixelData, pixels, JNI_ABORT);

    if (rc != 0 || jpegBuffer == nullptr || jpegSize == 0) {
        const char* error = (compressor != nullptr) ? tjGetErrorStr2(compressor) : nullptr;
        __android_log_print(
            ANDROID_LOG_WARN,
            "SlicerMozJpeg",
            "MozJPEG compress failed: rc=%d size=%dx%d quality=%d pitch=%d subsampling=%d flags=%d error=%s",
            rc,
            width,
            height,
            quality,
            pitch,
            subsampling,
            flags,
            error != nullptr ? error : "unknown"
        );
        if (jpegBuffer != nullptr) {
            tjFree(jpegBuffer);
        }
        tjDestroy(compressor);
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(jpegSize));
    if (result == nullptr) {
        tjFree(jpegBuffer);
        tjDestroy(compressor);
        return nullptr;
    }

    env->SetByteArrayRegion(result, 0, static_cast<jsize>(jpegSize), reinterpret_cast<jbyte*>(jpegBuffer));
    tjFree(jpegBuffer);
    tjDestroy(compressor);

    return result;
#else
    return nullptr;
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
