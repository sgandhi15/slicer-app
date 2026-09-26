# Slicer Performance Measurement

Phase 6 measurements use three complementary paths. Run numeric comparisons on the same physical device, Android build, thermal state, and app revision; emulator and cross-device values are not comparable.

## Production Tracing

Every worker item emits one `Processing metrics` log line under the `Slicer` tag. It includes:

- Total elapsed and process CPU time.
- Peak Java and native heap samples observed at stage boundaries.
- Conservative process and Java-heap estimates used by memory admission.
- Logical bytes read and written for completed Kotlin stream operations, including marker validation and metadata passes. Native transcoding reports bytes supplied by its bounded source buffer; native validation and provider failures do not expose complete byte counts.
- Elapsed time and invocation count for header inspection, memory admission, native decode/encode, metadata, original fallback, marker-level final validation, and publication. Legacy bitmap and visual stages are absent from production transcoding. Exceptional native or platform fallback decoding is not yet a separate timed stage.

Read the records with:

```bash
adb logcat -s Slicer:I '*:S'
```

Perfetto traces use matching `Slicer ...` async sections. UI state construction is exposed as `Slicer UI state`. The opt-in processing tests additionally sample heap every 20 ms during bitmap allocation and native encoding. Production boundary samples are lightweight guardrail data, not a replacement for Perfetto or Android Studio profiling when calibrating true RSS/PSS peaks.

## Correctness At Scale

The connected instrumentation suite exercises:

- In-memory Room queue insertion, serial claims, transactional completion, and exact summaries at 100, 400, and 1,000 rows. This is DAO scale coverage, not process-restart durability coverage.
- A 400-URI selection using an injected validator to verify off-main execution and no more than four validation workers. Real provider cancellation remains device/provider integration coverage.
- Paging-backed component rendering and stable-key scrolling with 400 synthetic rows, plus focused Room `PagingSource` ordering/filtering coverage. The macrobenchmark exercises Room-backed paging at larger scale.

Run the suite on an unlocked API 31 or newer device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

## Processing Scenarios

`ProcessingPerformanceTest` is opt-in so ordinary instrumentation runs stay deterministic. It covers a synthetic 100, 500, or 1,000-item engine loop that reuses a generated small JPEG, plus generated 12 MP and 24 MP stripe images through decode, progressive MozJPEG encode, metadata dispatch, and validation. These tests stop at the app-private prepared output; they do not measure destination publication, Room completion, permission release, or worker recovery. Direct native 12 MP and 24 MP cases measure combined native decoding and encoding under the production memory budget; fixture bitmap generation happens before the measured operation.

Run the default 100-image case and both representative sizes:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.slicer.processing.ProcessingPerformanceTest \
  -Pandroid.testInstrumentationRunnerArguments.slicer.performance=true \
  -Pandroid.testInstrumentationRunnerArguments.slicer.batchSize=100
```

Change `slicer.batchSize` to `500` or `1000` for the longer runs. Fixture generation and cleanup are outside the reported processing loop.

## UI Macrobenchmarks

The `:benchmark` module targets the signed, profileable, R8-optimized `com.example.slicer.benchmark` app variant, isolated from normal debug and release app data. It captures:

- Startup timing, frame timing, maximum process memory, constant-size `Slicer UI state` mapping time, and initial page loading while cold-starting 400 persisted results.
- Frame timing and maximum process memory during repeated scrolling over a 1,000-result data set.

Run on an unlocked physical device:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

AndroidX Benchmark writes JSON and Perfetto artifacts under `benchmark/build/outputs/`. Keep those artifacts with the device/API/build metadata when comparing revisions.

The benchmark setup receiver clears only the isolated benchmark variant's database before seeding each iteration. Do not use the benchmark package for real data.

## Current Policy

The initial measurements keep deliberately conservative production behavior:

- Image admission is device-aware rather than capped at 24 MP or 12 MP: it rejects encoding when Java-heap or whole-process headroom is insufficient and falls back safely.
- Native decoding and encoding share the available-system-memory budget after reserving at least 256 MiB or twice Android's low-memory threshold. All codec heap requests, including scan optimization and transient buffer growth, are charged to a shared allocation scope with a further 1 MiB application/native I/O reserve. Reported peaks include requested allocation sizes and headers, not total process RSS.
- Java-heap admission retains a 64 MiB coordination reserve and no per-pixel charge. No full-resolution bitmap is allocated in the production compression path.
- Exactly one image processor remains active. The current 24 MP peak leaves no evidence that two encoders are safe, and cleanup, cancellation, and output publication are intentionally single-flight.
- Provider I/O failures marked retryable receive at most three item attempts with 10-second then 20-second delays. Permission-loss `SecurityException`s and processing validation failures are treated as permanent rather than consuming retries; narrower transient-provider classification remains future work.

## Initial Reference Run

These predate the native decode-to-encode pipeline and are historical smoke measurements from a debug instrumentation run on an `SM-S931W` running Android 16 on 2026-08-01. The originating artifacts were not retained with the repository, so the values validate only the initial harness direction and must not be used as release regression thresholds.

| Scenario | Elapsed | Process CPU | Peak Java heap | Peak native heap |
|---|---:|---:|---:|---:|
| 100 small images | 7.04 s | 6.86 s | 40.3 MB | 9.3 MB |
| 1,000 small images | 34.10 s | 33.05 s | 50.5 MB | 9.3 MB |
| 12 MP full pipeline | 7.61 s | 7.38 s | 3.5 MB | 274.9 MB |
| 24 MP full pipeline | 14.43 s | 13.72 s | 3.0 MB | 347.9 MB |

The 12 MP and 24 MP figures were collected in one instrumentation process, so allocator retention can raise later heap samples. Establish release baselines from isolated repeated runs before setting CI thresholds or changing limits.

The pre-paging optimized macrobenchmark variant produced this historical five-iteration UI reference on the same device. Paging changes require a new baseline before comparisons are made:

| Scenario | Key median/percentile | Heap max median | Anonymous RSS max median |
|---|---:|---:|---:|
| Cold startup with 400 results | 207.6 ms initial display; 0.1 ms UI-state mapping | 27.4 MB | 56.3 MB |
| Scroll over 1,000 results | 7.1 ms frame CPU p95; 0.7 ms frame overrun p95 | 33.5 MB | 62.1 MB |

The first scroll iteration had visibly higher memory than the steady-state median, so future regression gates should retain both median and maximum values rather than comparing only one run.

## Tuning Rules

- Do not compare debug processing numbers with the optimized macrobenchmark target.
- Use medians across repeated runs and retain p95/p99 frame data.
- Change memory or retry constants only with traces from representative low-, medium-, and high-memory devices.
- Require cleanup correctness, no OOM, and no increase in failed or copied-original outcomes before accepting a throughput improvement.
- Evaluate two encoders only in an isolated experiment with a shared memory reservation; production concurrency remains one until both memory and lifecycle safety are demonstrated.

## Native allocation regression checks

Run the allocation-scope tests with host address, leak, and undefined-behavior checks:

```bash
g++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -fno-omit-frame-pointer -pthread -Iapp/src/main/cpp \
  app/src/main/cpp/codec_allocations.cpp \
  app/src/test/cpp/codec_allocations_test.cpp -o /tmp/slicer-codec-allocations-test
/tmp/slicer-codec-allocations-test
```

`MozJpegTranscodeTest` exercises the JNI path on Android, including the reported
4284 × 5712 dimensions, baseline and progressive inputs, full-resolution output,
shared memory rejection, scan optimization under a tight budget, early and late
cancellation, provider pipes and asset slices, corrupt input, grayscale, safe CMYK
preservation, and EXIF orientation. Its large-image fixture is synthetic; these
checks do not replace physical-device profiling with representative photographs.

The full native audit builds the same pinned codec with AddressSanitizer and
UndefinedBehaviorSanitizer. Pass the path to an existing v4.1.5 checkout (the
Android `.cxx` build contains one under `mozjpeg-src`):

```bash
cmake -S app/src/test/cpp -B /tmp/slicer-native-audit -G Ninja \
  -DMOZJPEG_SOURCE_DIR=/absolute/path/to/mozjpeg-src -DCMAKE_BUILD_TYPE=Debug
cmake --build /tmp/slicer-native-audit --target transcode_audit_test
/tmp/slicer-native-audit/transcode_audit_test
```

It sweeps memory budgets, cancellation checkpoints, truncation positions, and
input corruption for both baseline and progressive JPEGs, and injects partial
read failures and output write failures. `MozJpegDescriptorTest` separately checks
Android descriptor reuse, sparse asset offsets above 2 GiB, Java callback
exceptions, and protection against truncating the source at the Kotlin boundary.
