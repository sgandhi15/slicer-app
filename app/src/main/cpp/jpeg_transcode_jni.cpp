#include "jpeg_transcode.h"
#include <jni.h>
#include <cerrno>
#include <unistd.h>
#include <sys/stat.h>

namespace {
struct Cancellation {
    JNIEnv* env;
    jobject probe;
    jmethodID method;
};
bool cancelled(void* token) {
    auto* value = static_cast<Cancellation*>(token);
    const bool result = value->env->CallBooleanMethod(value->probe, value->method) == JNI_TRUE;
    return value->env->ExceptionCheck() || result;
}

bool positionInput(int descriptor, jlong offset) {
    if (offset < 0) return false;
    // Asset offsets are 64-bit even in the armeabi-v7a/x86 builds. Seek regular
    // descriptors to zero too: providers may return a previously read handle.
    const off64_t position = lseek64(descriptor, static_cast<off64_t>(offset), SEEK_SET);
    return position == offset || (position < 0 && offset == 0 && errno == ESPIPE);
}
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_example_slicer_processing_MozJpegNative_transcodeJpegNative(
    JNIEnv* env, jclass, jint inputFd, jint outputFd, jint width, jint height,
    jint quality, jlong budget, jobject probe, jlong inputOffset, jlong inputLength, jint scaleNumerator
) {
    if (!probe) return nullptr;
    jclass probeClass = env->GetObjectClass(probe);
    if (!probeClass) return nullptr;
    jmethodID method = env->GetMethodID(probeClass, "getAsBoolean", "()Z");
    env->DeleteLocalRef(probeClass);
    if (!method) return nullptr;
    Cancellation cancellation{env, probe, method};
    slicer::TranscodeResult result{slicer::TranscodeStatus::IoFailure, 0};
    struct stat inputStat{}, outputStat{};
    // Never truncate the input if descriptors accidentally refer to the same file.
    const bool inspected = fstat(inputFd, &inputStat) == 0 && fstat(outputFd, &outputStat) == 0;
    const bool sameFile = inspected &&
        inputStat.st_dev == outputStat.st_dev && inputStat.st_ino == outputStat.st_ino;
    if (sameFile) result.status = slicer::TranscodeStatus::SourceOutputCollision;
    const bool distinct = inspected && !sameFile;
    const int ownedInput = distinct ? dup(inputFd) : -1;
    const int ownedOutput = distinct ? dup(outputFd) : -1;
    FILE* input = ownedInput >= 0 ? fdopen(ownedInput, "rb") : nullptr;
    FILE* output = ownedOutput >= 0 ? fdopen(ownedOutput, "wb") : nullptr;
    if (!input && ownedInput >= 0) close(ownedInput);
    if (!output && ownedOutput >= 0) close(ownedOutput);
    // Input is sequential: content-provider pipes are supported without seeking.
    if (input && output && positionInput(ownedInput, inputOffset) &&
        ftruncate(ownedOutput, 0) == 0 && lseek(ownedOutput, 0, SEEK_SET) >= 0) {
        result = slicer::transcodeJpeg(input, output, width, height, quality,
                                     budget > 0 ? static_cast<uint64_t>(budget) : 0,
                                     cancelled, &cancellation, inputLength, scaleNumerator);
    }
    const bool inputClosed = !input || fclose(input) == 0;
    const bool outputClosed = !output || fclose(output) == 0;
    if ((!inputClosed || !outputClosed) && result.status == slicer::TranscodeStatus::Success) {
        result.status = slicer::TranscodeStatus::IoFailure;
    }
    if (env->ExceptionCheck()) return nullptr;
    jlong values[]{static_cast<jlong>(result.status), static_cast<jlong>(result.estimatedPeakBytes),
                   static_cast<jlong>(result.bytesRead)};
    jlongArray array = env->NewLongArray(3);
    if (array) env->SetLongArrayRegion(array, 0, 3, values);
    return array;
}
