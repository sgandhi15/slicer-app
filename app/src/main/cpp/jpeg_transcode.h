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
TranscodeResult transcodeJpeg(FILE* input, FILE* output, int width, int height, int quality,
                              uint64_t budgetBytes, bool (*cancelled)(void*), void* token,
                              int64_t inputLength = -1);
}
