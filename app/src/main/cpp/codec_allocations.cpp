#include "codec_allocations.h"

#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <algorithm>

struct Allocation;
struct SlicerAllocationScope {
    uint64_t budget;
    uint64_t used;
    uint64_t peak;
    Allocation* first;
    SlicerAllocationScope* previous;
    void (*failure)(void*);
    void* token;
};

struct alignas(std::max_align_t) Allocation {
    size_t bytes;
    SlicerAllocationScope* owner;
    Allocation* previous;
    Allocation* next;
};

namespace {
thread_local SlicerAllocationScope* currentScope = nullptr;

void allocationFailed() {
    if (currentScope && currentScope->failure) currentScope->failure(currentScope->token);
}
}

SlicerAllocationScope* slicer_begin_allocations(uint64_t budget, void (*failure)(void*), void* token) {
    auto* scope = static_cast<SlicerAllocationScope*>(std::calloc(1, sizeof(SlicerAllocationScope)));
    if (!scope) return nullptr;
    scope->budget = budget;
    scope->previous = currentScope;
    scope->failure = failure;
    scope->token = token;
    currentScope = scope;
    return scope;
}

uint64_t slicer_allocation_peak(const SlicerAllocationScope* scope) { return scope ? scope->peak : 0; }

void slicer_end_allocations(SlicerAllocationScope* scope) {
    if (!scope) return;
    // Some scan/trellis buffers are raw malloc allocations, not libjpeg pools.
    // Sweep any left behind when cancellation/error skips their normal free.
    while (scope->first) slicer_codec_free(scope->first + 1);
    currentScope = scope->previous;
    std::free(scope);
}

void* slicer_codec_malloc(size_t size) {
    if (size > SIZE_MAX - sizeof(Allocation)) {
        allocationFailed();
        return nullptr;
    }
    const size_t bytes = sizeof(Allocation) + size;
    if (currentScope) {
        const uint64_t requested = currentScope->used > UINT64_MAX - bytes ? UINT64_MAX :
            currentScope->used + bytes;
        currentScope->peak = std::max(currentScope->peak, requested);
        if (requested > currentScope->budget) {
            allocationFailed();
            return nullptr;
        }
    }
    auto* allocation = static_cast<Allocation*>(std::malloc(bytes));
    if (!allocation) {
        allocationFailed();
        return nullptr;
    }
    allocation->bytes = bytes;
    allocation->owner = currentScope;
    allocation->previous = nullptr;
    allocation->next = currentScope ? currentScope->first : nullptr;
    if (currentScope) {
        if (currentScope->first) currentScope->first->previous = allocation;
        currentScope->first = allocation;
        currentScope->used += bytes;
    }
    return allocation + 1;
}

void slicer_codec_free(void* pointer) {
    if (!pointer) return;
    auto* allocation = static_cast<Allocation*>(pointer) - 1;
    auto* owner = allocation->owner;
    if (owner) {
        if (allocation->previous) allocation->previous->next = allocation->next;
        else owner->first = allocation->next;
        if (allocation->next) allocation->next->previous = allocation->previous;
        owner->used -= allocation->bytes;
    }
    std::free(allocation);
}

void* slicer_codec_calloc(size_t count, size_t size) {
    if (size && count > SIZE_MAX / size) {
        allocationFailed();
        return nullptr;
    }
    void* pointer = slicer_codec_malloc(count * size);
    if (pointer) std::memset(pointer, 0, count * size);
    return pointer;
}

void* slicer_codec_realloc(void* pointer, size_t size) {
    if (!pointer) return slicer_codec_malloc(size);
    if (!size) { slicer_codec_free(pointer); return nullptr; }
    auto* allocation = static_cast<Allocation*>(pointer) - 1;
    // Account for the transient old+new peak, including scan buffer growth.
    void* replacement = slicer_codec_malloc(size);
    if (!replacement) return nullptr;
    std::memcpy(replacement, pointer, std::min(size, allocation->bytes - sizeof(Allocation)));
    slicer_codec_free(pointer);
    return replacement;
}
