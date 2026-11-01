#pragma once

#include <cstdint>
#include <cstdio>

namespace slicer {
enum class TranscodeStatus {
    Success, InvalidData, IoFailure, ResourceLimit, Cancelled, Unsupported, SourceOutputCollision
};
struct TranscodeResult {
    TranscodeStatus status;
    uint64_t estimatedPeakBytes;
    uint64_t bytesRead = 0;
};
// Streams are borrowed. The caller owns closing/flushing them and publishing output.
// scaleNumerator/8 shrinks the image while decoding (8 keeps the original size). A non-zero
// output size, no larger than the decoded size, is then reached with a smooth resample.
TranscodeResult transcodeJpeg(FILE* input, FILE* output, int width, int height, int quality,
                              uint64_t budgetBytes, bool (*cancelled)(void*), void* token,
                              int64_t inputLength = -1, int scaleNumerator = 8,
                              int outputWidth = 0, int outputHeight = 0);
}
