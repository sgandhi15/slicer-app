# Slicer Performance Measurement

Phase 6 measurements use three complementary paths. Run numeric comparisons on the same physical device, Android build, thermal state, and app revision; emulator and cross-device values are not comparable.

## Production Tracing

Every worker item emits one `Processing metrics` log line under the `Slicer` tag. It includes:

- Total elapsed and process CPU time.
- Peak Java and native heap samples observed at stage boundaries.
- Conservative process and Java-heap estimates used by memory admission.
- Logical bytes read and written for completed operations, including repeated validation and metadata passes. A provider failure can stop before its partial byte count is available.
- Elapsed time and invocation count for header inspection, memory admission, bitmap decode, native encoding, structural and visual validation, metadata, original fallback, final validation, and publication.

Read the records with:

```bash
adb logcat -s Slicer:I '*:S'
```

Perfetto traces use matching `Slicer ...` async sections. UI state construction is exposed as `Slicer UI state`. The opt-in processing tests additionally sample heap every 20 ms during bitmap allocation and native encoding. Production boundary samples are lightweight guardrail data, not a replacement for Perfetto or Android Studio profiling when calibrating true RSS/PSS peaks.

## Correctness At Scale

The connected instrumentation suite exercises:

- Queue insertion, serial claims, transactional completion, and exact summaries at 100, 400, and 1,000 rows.
- A 400-URI selection with provider work off the main thread and no more than four validation workers.
- Lazy result rendering and stable-key scrolling with 400 rows.

Run the suite on an unlocked API 31 or newer device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

## Processing Scenarios

`ProcessingPerformanceTest` is opt-in so ordinary instrumentation runs stay deterministic. It covers a real 100, 500, or 1,000-image small-file engine loop plus real 12 MP and 24 MP decode, progressive MozJPEG encode, metadata, and validation paths. Direct native 12 MP and 24 MP cases isolate encoder behavior while still obeying the production memory-admission policy.

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

- Startup timing, frame timing, maximum process memory, and `Slicer UI state` mapping time while cold-starting 400 persisted results.
- Frame timing and maximum process memory during repeated scrolling over a 1,000-result data set.

Run on an unlocked physical device:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

AndroidX Benchmark writes JSON and Perfetto artifacts under `benchmark/build/outputs/`. Keep those artifacts with the device/API/build metadata when comparing revisions.

The benchmark setup receiver clears only the isolated benchmark variant's database before seeding each iteration. Do not use the benchmark package for real data.

## Current Policy

The initial measurements keep deliberately conservative production behavior:

- Maximum image size remains 24 MP, or 12 MP on low-RAM devices.
- Whole-process admission retains a 16-bytes-per-pixel estimate plus at least 256 MiB system reserve.
- Java-heap admission uses a separate 4-bytes-per-pixel estimate plus 64 MiB reserve. Native bitmap and codec allocations are no longer subtracted from the Java limit and then charged a second time.
- Exactly one image processor remains active. The current 24 MP peak leaves no evidence that two encoders are safe, and cleanup, cancellation, and output publication are intentionally single-flight.
- Provider I/O failures receive at most three item attempts with 10-second then 20-second delays. Permission-loss `SecurityException`s are treated as permanent rather than consuming retries.

## Initial Reference Run

These are smoke measurements from a debug instrumentation run on an `SM-S931W` running Android 16 on 2026-08-01. They validate the harness and policy direction, but they are not release regression thresholds because the device was not prepared as a controlled benchmark runner.

| Scenario | Elapsed | Process CPU | Peak Java heap | Peak native heap |
|---|---:|---:|---:|---:|
| 100 small images | 7.04 s | 6.86 s | 40.3 MB | 9.3 MB |
| 1,000 small images | 34.10 s | 33.05 s | 50.5 MB | 9.3 MB |
| 12 MP full pipeline | 7.61 s | 7.38 s | 3.5 MB | 274.9 MB |
| 24 MP full pipeline | 14.43 s | 13.72 s | 3.0 MB | 347.9 MB |

The 12 MP and 24 MP figures were collected in one instrumentation process, so allocator retention can raise later heap samples. Establish release baselines from isolated repeated runs before setting CI thresholds or changing limits.

The optimized macrobenchmark variant produced this initial five-iteration UI reference on the same device:

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
