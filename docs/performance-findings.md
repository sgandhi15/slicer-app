# Performance Findings

This document tracks performance risks found during a static review of the app. No benchmark or device profiling has been performed yet, so each fix should be validated with representative images and batch sizes before being marked complete.

## Status Legend

- [ ] Not started
- [x] Completed and verified

## P0: Peak Memory During Compression

- [ ] Reduce peak memory usage for large images.

**Affected code:**

- `app/src/main/java/com/example/slicer/processing/JpegEncoding.kt:49`
- `app/src/main/java/com/example/slicer/processing/JpegEncoding.kt:122`
- `app/src/main/java/com/example/slicer/processing/JpegEncoding.kt:173`
- `app/src/main/java/com/example/slicer/processing/BitmapHelpers.kt:7`

**Risk:** A large image can have a decoded bitmap, an `IntArray`, an RGB `ByteArray`, encoded output, and a validation bitmap in memory around the same time. At the current 24-megapixel limit, this can cause long garbage-collection pauses, an `OutOfMemoryError`, or process termination on lower-memory devices.

**Suggested direction:** Measure the actual peak memory on supported devices, avoid full-image intermediate arrays where possible, process pixels in rows or chunks, and set a memory-safe image limit if the pipeline cannot be made streaming.

**Verification:** Process images near the supported pixel limit repeatedly on a low-memory device while monitoring Java and native heap usage. Confirm that processing completes without an OOM or severe GC pauses.

## P0: Metadata Processing Memory and I/O

- [ ] Make metadata preservation avoid repeated whole-file reads, copies, and full decodes.

**Affected code:**

- `app/src/main/java/com/example/slicer/processing/Metadata.kt:19`
- `app/src/main/java/com/example/slicer/processing/Metadata.kt:35`
- `app/src/main/java/com/example/slicer/processing/Metadata.kt:45`
- `app/src/main/java/com/example/slicer/processing/Metadata.kt:50`

**Risk:** Metadata preservation reads both files fully into memory, creates merged byte arrays, and performs additional full image validation decodes. This increases processing time and compounds the peak-memory risk for large files.

**Suggested direction:** Parse and copy JPEG metadata with bounded or streaming I/O. Avoid decoding the complete image solely to validate metadata insertion when structural JPEG validation is sufficient.

**Verification:** Compare peak memory and processing time before and after the change using large JPEGs with no metadata, normal EXIF metadata, and unusually large APP segments. Verify that preserved metadata and decoded pixels remain correct.

## P1: Main-Thread File Validation

- [ ] Move selected-image validation and provider I/O off the main thread.

**Affected code:**

- `app/src/main/java/com/example/slicer/home/HomeScreen.kt:207`
- `app/src/main/java/com/example/slicer/home/HomeScreen.kt:210`
- `app/src/main/java/com/example/slicer/home/HomeScreen.kt:229`
- `app/src/main/java/com/example/slicer/storage/Storage.kt:138`
- `app/src/main/java/com/example/slicer/storage/Storage.kt:250`

**Risk:** Returning from the picker performs MIME lookup, metadata queries, stream reads, and persisted-permission calls on the main thread for every selected URI. Large selections or slow cloud-backed providers can freeze rendering and may cause an ANR.

**Suggested direction:** Show a validation state immediately, perform provider queries and signature checks on `Dispatchers.IO`, and publish the completed selection back to the UI. Consider a documented or enforced batch-size limit.

**Verification:** Select a large batch from local storage and a deliberately slow document provider. Confirm with system traces or StrictMode that provider and stream I/O do not run on the main thread.

## P1: Non-Lazy Results List

- [ ] Use lazy composition for batch results.

**Affected code:**

- `app/src/main/java/com/example/slicer/home/UiComponents.kt:131`
- `app/src/main/java/com/example/slicer/home/UiComponents.kt:141`

**Risk:** `verticalScroll` with `results.forEachIndexed` composes and measures every result row, including off-screen rows. Opening a large result set can cause a long layout pass, allocation pressure, and slow scrolling.

**Suggested direction:** Render the results with `LazyColumn`, provide stable keys where possible, and keep the run summary outside or as a distinct lazy item.

**Verification:** Open and scroll result sets containing at least 100, 500, and 1,000 entries. Check frame timing and confirm that only visible or nearby rows are composed.

## P1: Quadratic Result Accumulation

- [ ] Avoid copying the complete result list after every processed file.

**Affected code:**

- `app/src/main/java/com/example/slicer/home/HomeScreen.kt:314`
- `app/src/main/java/com/example/slicer/home/HomeScreen.kt:315`

**Risk:** `batchResults = batchResults + result` allocates and copies an increasingly large list for every completed file. Total work grows quadratically with batch size and occurs through the main-thread progress callback.

**Suggested direction:** Use a snapshot-aware mutable collection, keep only lightweight progress state during processing, or publish results in bounded batches. Preserve cancellation behavior that reports already completed files.

**Verification:** Process increasingly large batches and profile main-thread allocation and callback time. Confirm that per-file result updates remain approximately constant-time.

## P2: MozJPEG SIMD Disabled

- [ ] Investigate enabling MozJPEG SIMD for supported Android ABIs.

**Affected code:**

- `app/src/main/cpp/CMakeLists.txt:56`

**Risk:** MozJPEG is built with `WITH_SIMD=OFF`, which can increase compression time, CPU consumption, battery usage, and cancellation latency.

**Suggested direction:** Determine the MozJPEG and NDK requirements for SIMD on each shipped ABI, enable it where supported, and retain a verified fallback for unsupported targets.

**Verification:** Benchmark the same image corpus with SIMD enabled and disabled on each supported ABI. Compare output validity, compression ratio, processing time, CPU usage, and binary size.

## Supporting Risks

- [ ] Move output-folder provider queries off the main thread (`HomeScreen.kt:239`, `HomeScreen.kt:275`, `Storage.kt:94`).
- [ ] Add lifecycle-safe cleanup for comparison bitmaps (`HomeScreen.kt:103`, `HomeScreen.kt:114`).
- [ ] Recycle the source preview bitmap when EXIF orientation creates a replacement (`Storage.kt:438`).
- [ ] Make long decode, native encode, metadata, and write operations respond more promptly to cancellation.
- [ ] Delete incomplete output documents after failure or cancellation (`BatchProcessor.kt:66`).
- [ ] Profile cold-start native library loading currently initiated from the startup effect.

## Baseline Test Matrix

Use this matrix when addressing the findings:

- Devices: low-memory physical device, current mid-range device, and emulator for diagnostics.
- Images: small JPEG, 12 MP JPEG, near-limit 24 MP JPEG, rotated EXIF JPEG, metadata-heavy JPEG, and malformed JPEG.
- Batches: 1, 20, 100, 500, and 1,000 files where practical.
- Providers: local MediaStore, local DocumentsProvider, removable storage, and cloud-backed provider.
- Measurements: frame timing, ANRs, Java heap, native heap, GC pauses, processing duration, cancellation latency, and output correctness.
