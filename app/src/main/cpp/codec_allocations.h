#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct SlicerAllocationScope SlicerAllocationScope;
// Each thread has an independent scope. Failure must leave the codec operation
// (the caller uses libjpeg's established longjmp error boundary).
SlicerAllocationScope* slicer_begin_allocations(uint64_t budget, void (*failure)(void*), void* token);
uint64_t slicer_allocation_peak(const SlicerAllocationScope* scope);
void slicer_end_allocations(SlicerAllocationScope* scope);
void* slicer_codec_malloc(size_t size);
void* slicer_codec_calloc(size_t count, size_t size);
void* slicer_codec_realloc(void* pointer, size_t size);
void slicer_codec_free(void* pointer);

#ifdef __cplusplus
}
#endif
