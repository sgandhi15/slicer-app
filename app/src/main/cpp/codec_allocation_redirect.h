#pragma once

// Force-included only when compiling the pinned MozJPEG C sources. Including
// stdlib first avoids rewriting libc declarations. The application and libc's
// own FILE allocations continue to use their normal allocators.
#include <stdlib.h>
#include "codec_allocations.h"
#define malloc slicer_codec_malloc
#define calloc slicer_codec_calloc
#define realloc slicer_codec_realloc
#define free slicer_codec_free
