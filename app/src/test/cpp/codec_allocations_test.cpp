// Host regression checks; see BENCHMARKING.md for the sanitizer command.
#include "codec_allocations.h"
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <setjmp.h>
#include <thread>

namespace {
jmp_buf failureBoundary;
void fail(void*) { longjmp(failureBoundary, 1); }

void testScope() {
    auto* scope = slicer_begin_allocations(4096, nullptr, nullptr);
    assert(scope);
    auto* memory = static_cast<unsigned char*>(slicer_codec_calloc(16, 4));
    assert(memory);
    assert(reinterpret_cast<uintptr_t>(memory) % alignof(std::max_align_t) == 0);
    for (int i = 0; i < 64; ++i) assert(memory[i] == 0);
    std::memset(memory, 123, 64);
    auto* grown = static_cast<unsigned char*>(slicer_codec_realloc(memory, 512));
    assert(grown);
    for (int i = 0; i < 64; ++i) assert(grown[i] == 123);
    assert(slicer_allocation_peak(scope) >= 64 + 512);
    slicer_codec_free(grown);
    assert(slicer_codec_malloc(4000)); // Released blocks make the budget reusable.
    assert(!slicer_codec_malloc(1024));
    // Simulate buffers left behind by a codec longjmp: ending the scope sweeps them.
    slicer_end_allocations(scope);
}
}

int main() {
    testScope();
    std::thread other(testScope);
    testScope();
    other.join();
    auto* scope = slicer_begin_allocations(4096, fail, nullptr);
    assert(scope);
    if (setjmp(failureBoundary) == 0) {
        assert(slicer_codec_malloc(1024));
        slicer_codec_malloc(8192);
        assert(false);
    }
    assert(slicer_allocation_peak(scope) > 4096);
    slicer_end_allocations(scope);
    assert(!slicer_codec_calloc(SIZE_MAX, 2));
    assert(!slicer_codec_malloc(SIZE_MAX));
    // Allocations outside a transcode scope remain usable by JPEG validation.
    void* pointer = slicer_codec_malloc(32);
    assert(pointer);
    slicer_codec_free(pointer);
}
