# Slicer

Slicer is an Android JPEG compression app built with Kotlin, Jetpack Compose, and MozJPEG. This document records the current scalability and reliability problems in the image-processing pipeline and the intended solutions.

The immediate goal is to make batches containing hundreds of images safe, durable, responsive, and recoverable before expanding the app's feature set.

## Baseline Processing Model

Before the durable-queue work, the implementation:

- Selects multiple JPEG URIs through Android's document picker.
- Validates selected files in the picker callback.
- Processes images sequentially in a coroutine owned by the Compose screen.
- Fully decodes each source image into an Android bitmap.
- Converts all bitmap pixels into intermediate ARGB and RGB arrays.
- Compresses the RGB array with MozJPEG through JNI.
- Decodes generated output to validate it.
- Reads complete source and destination files into memory to preserve metadata.
- Stores progress and all results in Compose state.
- Writes outputs directly into the selected destination.

Sequential processing currently prevents multiple full-size images from being decoded concurrently. However, it does not solve the high peak memory use of one image, the amount of repeated work per image, or the lack of durable background execution.

## Problems And Solutions

### 1. High Peak Memory Use

#### Problem

Compression creates several large in-memory representations of the same image:

- An ARGB bitmap, approximately four bytes per pixel.
- An `IntArray` containing all pixels, approximately four bytes per pixel.
- An RGB `ByteArray`, approximately three bytes per pixel.
- Native encoder buffers.
- The encoded JPEG as another `ByteArray`.
- Additional decoded bitmaps and byte arrays during output and metadata validation.

A 24-megapixel image can require approximately 96 MB for the bitmap, 96 MB for the pixel array, and 72 MB for RGB data before native and validation allocations are included. Peak memory can therefore reach several hundred megabytes for one image. The existing 24-megapixel limit is not a guarantee that an image is safe on a particular device.

Hundreds of images are not all retained as bitmaps simultaneously, but any single large image can still cause excessive garbage collection or an out-of-memory crash.

#### Solution

- Keep compression concurrency at one image until per-image memory is reduced and measured.
- Remove the full-image `IntArray` and RGB `ByteArray` conversion.
- Pass Android bitmap pixels directly to native code using `AndroidBitmap_lockPixels`, with the correct pixel format handling.
- Write native encoder output directly to a file descriptor or stream instead of returning a large JPEG `ByteArray` through JNI.
- Move toward native scanline decoding and encoding if direct bitmap compression is still too memory-heavy.
- Stream JPEG metadata instead of loading complete source, destination, and merged files into memory.
- Replace the fixed pixel limit with a conservative, device-aware safety policy.
- Reject or safely copy an original when the app cannot guarantee enough memory for compression.

The first practical engine improvement should be direct bitmap-to-native encoding. Fully streaming native decoding is a larger follow-up optimization.

### 2. Processing Is Not Durable

#### Problem

The batch job is launched from a coroutine scope owned by the Compose screen. Selection, progress, results, and the active job are primarily stored in `remember` state.

This means a long batch can be cancelled or forgotten when:

- The activity is recreated.
- The device rotates or configuration changes recreate the activity.
- Android kills the process under memory pressure.
- The user backgrounds the app for an extended period.
- The composition leaves the screen unexpectedly.

There is no persisted queue, checkpoint, resume behavior, or durable record of completed files.

#### Solution

- Represent every run as a persisted batch.
- Store batch and image records in Room.
- Process the batch with a `CoroutineWorker` managed by WorkManager.
- Use a foreground worker and progress notification for long, user-initiated batches.
- Pass only a batch ID to WorkManager because WorkManager input data is size-limited.
- Let a `ViewModel` expose persisted batch state to Compose through `StateFlow`.
- Resume queued work after activity or process recreation.
- Record a terminal state and error for each image.

Suggested item states:

```text
Queued -> Processing -> Compressed
                     -> CopiedOriginal
                     -> Failed
                     -> Cancelled
```

The UI should observe the worker and database. It should not own the compression job.

### 3. Too Many Full-Image Passes

#### Problem

A successful image may currently require:

1. A bounds decode.
2. A full source decode.
3. A complete pixel-array conversion.
4. MozJPEG compression.
5. A full decode of the compressed output for validation.
6. Complete reads of source and destination files for metadata handling.
7. Another destination decode.
8. Construction of a complete merged JPEG array.
9. Another full decode for merged-output validation.
10. A rewrite of the destination.

Runtime therefore scales heavily with total megapixels and storage-provider latency, not only image count. These passes also create substantial allocation and garbage-collection pressure.

#### Solution

- Read source dimensions once and retain them in the persisted item record.
- Encode once under normal conditions.
- Validate JPEG structure, dimensions, encoder completion, and final byte count without repeatedly decoding the complete image.
- Reserve expensive visual decode validation for suspicious or exceptional output.
- Parse and copy only JPEG header metadata segments.
- Stream the compressed scan data rather than rebuilding complete files in memory.
- Avoid reopening remote or cloud-backed content URIs more than necessary.
- Add measurements around decode, encode, metadata, validation, and destination-write stages.

### 4. Sequential Throughput

#### Problem

Every image is processed one after another. This protects the current memory-heavy implementation from concurrent bitmap allocations, but large batches can take many minutes.

Simply adding parallel coroutines now would multiply peak memory use and make crashes more likely.

#### Solution

- Keep one active encoder as the safe default.
- Improve throughput first by removing copies, redundant decodes, and repeated file reads.
- Benchmark the optimized engine on low-, medium-, and high-memory devices.
- Consider bounded parallelism only after memory use is predictable.
- If parallelism is introduced, derive the worker count from available memory and CPU capability and enforce a small hard maximum.

One encoder may remain the correct default for most phones. Parallel encoding is an optional measured optimization, not the first fix.

### 5. Slow Cancellation And Partial Outputs

#### Problem

Native MozJPEG compression is synchronous and does not currently expose cancellation checkpoints. Cancelling a batch may therefore appear unresponsive until the current image finishes its native encode.

The destination document is also created before processing finishes. Failed compression, failed fallback copying, cancellation, or process death can leave empty or partially written output files. MediaStore output is not currently hidden with `IS_PENDING` while being written.

#### Solution

- Produce one app-controlled temporary output at a time.
- Validate the temporary output before publishing it.
- Create or copy to the final destination only after processing succeeds.
- Delete temporary files after success, failure, or cancellation.
- Use `IS_PENDING=1` for MediaStore entries and clear it only after the complete file is ready.
- Delete failed SAF destination documents when final copying does not complete.
- Treat cancellation as "stop after the current image" unless the native encoder gains safe interruption support.
- Persist cancellation state so a cancelled batch is distinguishable from a crash or ordinary failure.

### 6. Main-Thread Selection Validation

#### Problem

The picker callback validates every URI synchronously. Each validation can request a MIME type, query a display name, and open a stream to check the JPEG header. Hundreds of provider calls can block the main thread before the screen updates.

Cloud-backed or otherwise slow document providers make this substantially worse.

#### Solution

- Return from the picker callback immediately with a validating state.
- Validate URIs on `Dispatchers.IO`.
- Use limited concurrency for lightweight validation, such as four workers.
- Display progress such as `Validating 34 of 200`.
- Report unsupported or inaccessible files after validation.
- Allow validation cancellation when the user changes the selection.

### 7. Persisted URI Permission Limits

#### Problem

The app requests persistent read permission for every selected image and never releases those grants. Android limits the number of persisted grants an app can retain. Repeated large selections can exhaust this allowance, and the current code silently ignores individual grant failures.

Persisting URI permissions also does not currently make a batch durable because the selected URI list and queue are not persisted.

#### Solution Options

#### Option A: Persist External URIs

- Persist grants only for images actually placed in a durable queue.
- Release each grant when its image reaches a terminal state.
- Surface permission failures to the user.
- Enforce a batch-size policy if the platform grant limit can be reached.

This avoids temporary source duplication but remains dependent on external providers and grant limits.

#### Option B: Stage Inputs Locally

- Copy selected sources into app-controlled storage before processing.
- Persist stable local paths in the queue.
- Check available storage before staging.
- Delete staged inputs as soon as their output is safely published or the batch is discarded.
- Recover and clean abandoned staging files after crashes.

This is the recommended option for truly durable large batches, but it can temporarily require storage close to the total source size. The final product decision must balance durability against temporary disk usage.

### 8. Inefficient Result State Updates

#### Problem

Each completed result is appended using immutable list addition. This copies the growing list for every image and creates quadratic cumulative list-copy work. The full result list is also held in Compose state.

#### Solution

- Write each item result directly to Room.
- Update only small progress counters in active UI state.
- Observe persisted result rows from the `ViewModel`.
- Calculate and persist aggregate counts and byte totals incrementally as items finish.
- Avoid rebuilding the complete result collection for every progress event.

### 9. Non-Lazy Results Rendering

#### Problem

The results screen uses a vertically scrolling `Column` and composes every row at once. Hundreds of results increase composition cost and memory use, while larger batches can cause slow transitions and janky scrolling.

The summary also scans the complete result list several times during composition to calculate counts and byte totals.

#### Solution

- Render results with `LazyColumn`.
- Use stable database item IDs as Compose keys.
- Load rows from persisted state, using paging if batch sizes become very large.
- Read precomputed summary values instead of rescanning every row during recomposition.
- Keep the comparison screen responsible for loading only the selected result's previews.

The setup image grid is already lazy and should retain that behavior.

### 10. Output-Folder Enumeration

#### Problem

Before a batch starts, the app reads every existing filename in the output folder into memory to avoid collisions. The cost depends on the total folder size, not the selected batch size. Folders containing thousands of existing images can delay startup.

#### Solution

- Avoid enumerating the complete destination folder.
- Generate collision-resistant names using the source name and a short batch-specific suffix, or query only the exact candidate name.
- Keep an in-memory set only for names created by the active batch.
- Consider an optional per-batch subfolder if that matches the intended user experience.
- Handle provider-side automatic renaming by reading the actual created document name when necessary.

### 11. Release Optimization

#### Problem

Release optimization is currently disabled. Image decode and native compression dominate runtime, so this is not the primary bottleneck, but disabling optimization increases application bytecode size and prevents normal release shrinking and optimization.

#### Solution

- Enable normal release shrinking and optimization once release rules are verified.
- Add any required JNI and model keep rules.
- Verify release builds independently from debug performance measurements.

### 12. Missing Scale And Failure Coverage

#### Problem

Current tests do not exercise realistic large batches, memory pressure, lifecycle interruption, slow providers, partial outputs, or permission exhaustion. Performance regressions can therefore ship without being detected.

#### Solution

Add automated tests and benchmark scenarios covering:

- 100, 500, and 1,000 small JPEGs.
- Representative 12 MP and 24 MP JPEGs.
- Low-memory device profiles.
- Corrupted and unsupported files.
- Metadata-heavy JPEGs, including EXIF, ICC, IPTC, and comments.
- Cancellation during decode, encode, metadata handling, and final publishing.
- Activity recreation and process restart.
- Full destination storage.
- Slow, unavailable, and cloud-backed document providers.
- Destination permission loss.
- Persisted URI grant exhaustion.
- Duplicate source names and large existing output folders.
- Worker retry and resume behavior.

Measurements should include peak Java heap, peak native memory, time per stage, total batch time, bytes read and written, UI frame stability, and cleanup correctness.

## Target Architecture

### Persistence

Room should contain a batch record and one item record per selected image.

Suggested batch data:

- Batch ID.
- Creation and completion timestamps.
- Desired quality.
- Destination URI.
- Overall state.
- Total, completed, compressed, copied, failed, and cancelled counts.
- Total input bytes, output bytes, and saved bytes.
- Last batch-level error.

Suggested item data:

- Item ID and batch ID.
- Original display name and source reference.
- Staged input path or persisted source URI.
- Intended and actual output names.
- Output URI.
- Width, height, and source byte count.
- Current state and attempt count.
- Output byte count and bytes saved.
- Error or fallback message.

### Background Work

The WorkManager worker should:

1. Load the batch and next queued item.
2. Mark the item as processing.
3. Check cancellation and destination availability.
4. Prepare or open the stable source.
5. Compress to a temporary output.
6. Preserve metadata and validate the temporary output.
7. Copy the original to the temporary output when compression is unsafe or not smaller.
8. Publish the completed temporary output to the destination.
9. Persist the item result and update batch aggregates transactionally.
10. Release source permissions and delete staged or temporary files when safe.
11. Continue with the next queued item.
12. Persist the final batch state.

Retries must be limited and should distinguish transient provider errors from permanent decode or format errors.

### UI State

Compose screens should receive immutable state from a `ViewModel`:

- Selection and validation progress.
- Current batch identity and state.
- Current item name and overall progress.
- Incremental summary counters.
- A lazy stream of result rows.
- Recoverable errors and required user actions.

Navigation should not determine whether processing continues. Returning to the app should reconnect to the active or latest batch.

## Expected Behavior For Hundreds Of Images

With the target architecture:

- Selecting hundreds of files does not block the main thread.
- The queue survives activity and process recreation.
- Only one large source is actively decoded by default.
- Peak memory is bounded by the optimized per-image pipeline.
- Progress continues through a foreground worker when the app is backgrounded.
- Cancellation stops queue advancement and does not publish partial files.
- Completed results remain available without keeping the full collection in Compose memory.
- Temporary inputs and outputs are cleaned deterministically.
- Failed images do not prevent later queued images from being attempted.

## Implementation Order

### Phase 1: Durable Queue

- Add Room batch and item entities.
- Add a repository and `ViewModel` state flow.
- Move processing into a foreground `CoroutineWorker`.
- Persist progress, results, cancellation, and recovery state.

Phase 1 is complete when:

- Pressing Start commits the batch and its source URI rows before slow provider metadata or destination enumeration begins.
- The foreground worker prepares and processes persisted items one at a time.
- Room is the source of truth for progress, results, cancellation, and interrupted-item recovery.
- WorkManager owns system-stop retries, while application startup reconciles an unfinished Room queue with unique work.
- Activity recreation reconnects to the persisted batch without restarting or duplicating it.

Temporary output publishing, scalable selection validation, URI staging or grant cleanup, lazy result rendering, and encoder memory changes remain in their later phases below.

### Phase 2: Safe File Lifecycle

- Introduce one-at-a-time temporary outputs.
- Publish outputs only after success.
- Add MediaStore `IS_PENDING` handling.
- Delete partial SAF documents and abandoned temporary files.
- Define retry and cancellation behavior.

Phase 2 is complete when:

- Compression, metadata preservation, and original-file fallback operate only on one deterministic app-private temporary file at a time.
- A destination document is not created until the temporary output has passed processing validation.
- Default-folder MediaStore rows remain `IS_PENDING=1` until their complete contents have been written, then become visible with `IS_PENDING=0`.
- SAF outputs use an item-specific recovery suffix because SAF has no pending-row transaction; the provider's actual created name is persisted with the result.
- A failed or system-interrupted publication deletes its known MediaStore or SAF destination before the item can be retried.
- Startup and worker recovery remove abandoned temporary files, orphaned pending MediaStore rows, and tracked incomplete destination documents.
- User cancellation stops queue advancement after the current image, while a WorkManager stop cleans and requeues the interrupted image.
- Transient file and provider failures retry at most three item attempts using fresh, exponentially delayed WorkManager requests.
- If an incomplete output cannot be confirmed deleted, its item remains blocked in recoverable state and is never reused, overwritten, or requeued until cleanup succeeds.

### Phase 3: Scalable Selection And Results

- Move URI validation off the main thread.
- Implement the chosen persisted-URI or local-staging policy.
- Release grants and clean staged files deterministically.
- Replace result `Column` rendering with `LazyColumn`.
- Use persisted incremental summaries.

Phase 3 is complete when:

- The picker callback returns immediately while duplicate-free URI validation runs on bounded `Dispatchers.IO` concurrency with observable progress state.
- Replacing a selection cancels the prior request logically, and a late provider response cannot overwrite the newer selection.
- Unsupported and inaccessible files are reported separately after validation without blocking selection changes.
- Source access uses a bounded persisted-URI policy: grants are acquired only after the batch and item rows exist, partial acquisition is rolled back, and a batch contains at most 400 source grants.
- Terminal source grants and legacy unowned document grants are released idempotently, while destination tree grants and sources still referenced by queued or processing items are retained.
- Startup and manual recovery fail unfinished rows whose source grants were lost before enqueuing work, serialized with output cleanup so they cannot race a running worker.
- Result rows render in one `LazyColumn` keyed by persisted item IDs, while summary counts and byte totals come from the batch record rather than scans during composition.
- Normal item transitions update persisted summary counters and byte totals by transactional deltas; full item scans are reserved for reconciliation boundaries.

The selected policy keeps external source URIs rather than staging complete inputs locally. Source grants are not retained solely for comparison, so loading an original after process restart is best-effort once its item is terminal. Local staging remains a future option if batches beyond the persisted-grant policy are required.

### Phase 4: Memory-Safe Encoding

- Remove full-image ARGB and RGB intermediate arrays.
- Pass bitmap pixels directly to native encoding.
- Write native output directly to a temporary file descriptor.
- Add conservative memory checks and fallback behavior.

### Phase 5: Streaming Metadata And Validation

- Stream JPEG metadata segments.
- Reduce repeated full-image decodes.
- Add structural and dimension validation.
- Keep expensive visual validation only for exceptional output.

Phase 5 is complete when:

- Source dimensions are read from a cancellation-aware, bounded JPEG header scan and checked again against the decoded bitmap before encoding.
- Every native attempt must contain a complete, dimension-matching JPEG structure, including valid progressive scans, entropy stuffing, restart markers, and a final EOI marker.
- Normal first-attempt MozJPEG output is accepted without another bitmap decode; alternate attempts or an unexpected encoder profile retain one expensive visual safeguard.
- JFIF density, EXIF, ordered RGB ICC, IPTC/Photoshop, and comments are copied segment-by-segment without complete source, encoded, or merged JPEG byte arrays; linked or unknown APP payloads are omitted rather than retaining stale offsets.
- Source Adobe APP14 data is not transplanted onto newly encoded color data; an encoder-owned APP14 segment is retained.
- Metadata is written to a size-limited app-private sidecar, flushed, structurally validated, and atomically moved over the encoded temporary file so cancellation or failure cannot partially rewrite the canonical output.
- Both compressed output and original-file fallback pass complete structural validation before publication; malformed or truncated originals are failed rather than copied to a user-visible destination.

### Phase 6: Measurement And Tuning

- Build large-batch integration tests and benchmarks.
- Measure Java heap, native memory, CPU time, I/O, and UI responsiveness.
- Tune pixel limits and retry policies.
- Evaluate bounded parallelism only if measurements show a safe benefit.
- Enable and verify release optimization.

## Initial Engineering Priorities

The first priorities are reliability and data safety, not parallel speed:

1. A batch must survive screen and process lifecycle events.
2. Cancellation and failure must not leave partial user-visible files.
3. One image must fit safely within a predictable memory budget.
4. Large selections and result lists must not block or overwhelm Compose.
5. Performance changes must be driven by representative benchmarks.

Parallel compression should only be considered after these guarantees are in place.
