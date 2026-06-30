# MozJPEG Bulk Compressor User Stories

## Product Direction

Slicer is an offline Android image compressor focused first on JPEG bulk optimization using MozJPEG. The app should feel trustworthy, fast, and purpose-built: users select many images, choose an output folder, compress locally, keep metadata intact, and always receive one output file for every input file.

The first version should be JPEG-only. MozJPEG is specialized for JPEG, so JPEG input and JPEG output gives the safest path to high-quality compression, metadata preservation, and predictable results.

## Core Compression Rules

- Process everything offline on-device.
- Preserve all JPEG metadata markers by default, including EXIF, ICC, XMP, IPTC/Photoshop data, comments, and thumbnails where possible.
- Never reduce the output count. If the user selects 100 input JPEGs, the destination should contain 100 output JPEGs.
- If compression succeeds and the compressed file is smaller than or equal to the original, save the compressed file.
- If compression fails, metadata copying fails, or the compressed file is larger than the original, copy the original file to the output folder.
- Never overwrite source files in the first version.
- Keep arithmetic coding disabled for compatibility.
- Use Squoosh-like MozJPEG defaults for the first preset.

## Initial MozJPEG Defaults

These defaults are close to Squoosh's safe MozJPEG behavior:

```text
quality = 75
baseline = false
arithmetic = false
progressive = true
optimize_coding = true
smoothing = 0
color_space = YCbCr
quant_table = 3
auto_subsample = true
trellis_multipass = false
trellis_opt_zero = false
trellis_opt_table = false
trellis_loops = 1
separate_chroma_quality = false
chroma_quality = 75
```

## Personas

- Casual user: wants to reduce photo sizes before sharing or backing up.
- Power user: wants predictable batch processing and full control over quality/output.
- Privacy-focused user: wants compression without uploading images to a server.
- Creator/blogger: wants smaller images while preserving important metadata and color profiles.

## MVP User Stories

### 1. Select Multiple JPEG Images

As a user, I want to select multiple JPEG images, so that I can compress a batch in one run.

Acceptance criteria:

- User can pick multiple `.jpg` and `.jpeg` files through Android's system picker.
- Unsupported files are clearly shown as unsupported or skipped before processing.
- The app shows the number of selected images.
- The app does not request broad storage permissions unnecessarily.

### 2. Select An Output Folder

As a user, I want to choose where compressed images are saved, so that my originals remain untouched.

Acceptance criteria:

- User can pick a destination folder through Android's folder picker.
- The selected output location is visible before compression starts.
- Source files are never overwritten in the MVP.
- If a filename conflict exists, the app creates a safe unique output name.

### 3. Choose Compression Quality

As a user, I want a simple quality control, so that I can balance file size and image quality.

Acceptance criteria:

- User can adjust JPEG quality from 1 to 100.
- Default quality is 75.
- UI explains that lower values create smaller files but more visible quality loss.
- The app remembers the last selected quality for future sessions.

### 4. Use A Squoosh-Like Balanced Preset

As a user, I want a sensible default preset, so that I do not need to understand advanced JPEG settings.

Acceptance criteria:

- Default preset uses progressive JPEG.
- Default preset enables optimized Huffman coding.
- Arithmetic coding remains disabled.
- Advanced settings are hidden in the MVP unless explicitly added later.

### 5. Preserve Metadata By Default

As a user, I want my image metadata preserved, so that camera information, color profile, and other data stay intact.

Acceptance criteria:

- EXIF metadata is copied to the output JPEG.
- ICC profile is copied to the output JPEG.
- XMP/IPTC/comments are copied where present.
- If metadata cannot be copied safely, the original file is copied to output instead of saving a stripped compressed version.

### 6. Guarantee One Output Per Input

As a user, I want every selected input image to produce an output file, so that batch counts are predictable.

Acceptance criteria:

- For `N` selected input JPEGs, the output folder contains `N` resulting JPEGs after a completed run.
- If compression makes a file larger, the original file is copied to output.
- If compression fails for a file, the original file is copied to output.
- The results screen shows whether each file was compressed or copied unchanged.

### 7. Compare Original And Output Sizes

As a user, I want to see how much space was saved, so that I know whether compression helped.

Acceptance criteria:

- Each image row shows original size, output size, and saved percentage after processing.
- Final summary shows total input size, total output size, and total space saved.
- Files copied unchanged are included in the totals.

### 8. Process In The Background Safely

As a user, I want bulk compression to continue reliably, so that large batches do not fail if I leave the screen.

Acceptance criteria:

- Compression runs off the main thread.
- Long-running batches show ongoing progress.
- The app avoids processing too many images in parallel.
- If the operation is cancelled, already written files remain valid and the app reports partial completion.

### 9. Show Clear Progress

As a user, I want to know what the app is doing, so that I can trust long-running compression jobs.

Acceptance criteria:

- UI shows current file name or position, such as `12 of 100`.
- UI shows success, copied-original, failed, and skipped counts.
- User can cancel before or during processing.
- Completion screen is clear and does not disappear automatically.

### 10. Protect User Privacy

As a user, I want compression to happen offline, so that my private photos are not uploaded.

Acceptance criteria:

- App does not require internet permission for MVP.
- UI clearly states that processing happens on-device.
- No analytics or network calls are added in the MVP.

## Later User Stories

### 11. Folder Batch Import

As a user, I want to select a folder, so that I can compress all JPEGs inside it.

Acceptance criteria:

- User can select a folder as input.
- App scans direct child JPEG files first.
- Recursive folder scanning is a separate setting.
- Unsupported files are listed before processing.

### 12. Resize Before Compression

As a user, I want to resize large images, so that I can reduce file size further.

Acceptance criteria:

- User can set max width or max height.
- Aspect ratio is preserved by default.
- Metadata preservation behavior is clearly explained because unchanged EXIF dimensions/thumbnails may become inaccurate after resizing.

### 13. Advanced MozJPEG Controls

As a power user, I want advanced encoder settings, so that I can tune output for specific needs.

Acceptance criteria:

- User can toggle progressive JPEG.
- User can toggle optimize coding.
- User can choose chroma subsampling behavior.
- App warns against compatibility-risk settings such as arithmetic coding if ever exposed.

### 14. Target Size Mode

As a user, I want to set a target file size, so that each output image stays under a limit.

Acceptance criteria:

- App tries multiple quality values to reach the target size.
- App uses a bounded number of attempts.
- If target size cannot be reached, app either saves the smallest attempt or copies original based on user setting.

### 15. Non-JPEG Input

As a user, I want to convert PNG/WebP/HEIC to JPEG, so that I can compress more file types.

Acceptance criteria:

- App clearly states that non-JPEG files will become JPEG.
- Alpha/transparency handling is explicit.
- Metadata preservation is best-effort and clearly labeled.
- This is not part of the MozJPEG-first MVP.

## App Polish Checklist

The app should not feel like a generic Android Studio template. Build the product identity early.

### Name And Positioning

- Decide whether `Slicer` remains the final app name.
- Short tagline: `Offline bulk JPEG compression` or `Private photo compression on-device`.
- Make privacy and predictable output count part of the identity.

### Logo And Icon

- Create a simple adaptive launcher icon.
- Avoid generic mountain/photo icons if possible.
- Direction ideas: compressed image stack, precise blade/slice mark, pixel block reducing into smaller block, or a folded JPEG sheet.
- Prepare foreground and background layers for Android adaptive icons.
- Check icon readability at small sizes.

### Splash Screen

- Add Android 12+ splash screen branding.
- Use the app icon centered with a clean background color.
- Keep splash fast; do not fake loading.
- After splash, land directly on the main import screen.

### Visual Style

- Use a focused visual language: technical but approachable.
- Avoid default purple Material template colors.
- Suggested palette direction: deep charcoal background, warm accent, clean cards, strong success green for saved size.
- Use typography hierarchy intentionally: large action title, compact metadata rows, clear numeric summaries.

### Main Screen

- Primary action should be obvious: `Select JPEGs`.
- Secondary action: `Choose Output Folder`.
- Show current compression preset and quality before start.
- Include privacy reassurance: `Offline. No uploads.`
- Empty state should explain the workflow in one or two lines.

### Processing Screen

- Show batch progress as a large count, such as `24 / 100`.
- Show current file name in a compact way.
- Show live totals: compressed, copied original, failed/skipped.
- Make cancel action visible but not easy to tap accidentally.

### Result Screen

- Show total saved size prominently.
- Show original total and output total.
- Show list of files with status badges: `Compressed`, `Copied original`, `Failed`.
- Provide an action to open the output folder if Android allows it.

### Settings

- Quality default: 75.
- Metadata default: keep all.
- Fallback default: copy original if larger.
- Progressive default: enabled.
- Internet/network behavior: no internet in MVP.

### Trust And Safety

- Do not overwrite originals.
- Make fallback behavior explicit before compression.
- Report failures per file rather than failing the whole batch.
- Add a small privacy statement in the app.

### Performance Feel

- Keep UI responsive while processing.
- Limit native compression concurrency initially to avoid memory pressure.
- Decode/process one or two images at a time first.
- Avoid loading every full-size image into memory at once.

## Suggested Build Phases

### Phase 1: Product Shell

- Replace template greeting with a real Compose home screen.
- Add app name, empty state, quality control, and disabled start button until inputs are selected.
- Add proper colors/theme instead of template defaults.

### Phase 2: File Selection

- Add multi-image picker for JPEG files.
- Add output folder picker.
- Display selected file count and destination.

### Phase 3: MozJPEG Native Spike

- Add NDK/CMake setup.
- Build/link MozJPEG for `arm64-v8a` and `x86_64`.
- Add a minimal JNI function that compresses one JPEG byte array.
- Verify output opens on Android and desktop viewers.

### Phase 4: Batch Engine

- Process selected files sequentially first.
- Compare compressed size with original size.
- Copy original when compression is larger or fails.
- Preserve metadata or fallback to original if metadata preservation fails.

### Phase 5: Results And Polish

- Add result summary screen.
- Add per-file status list.
- Add launcher icon and splash screen.
- Add UI copy explaining offline behavior and fallback behavior.

## Open Product Questions

- Should `Slicer` stay as the public app name?
- Should copied-original files keep the exact original filename or include a suffix?
- Should compressed files keep original filenames by default?
- Should the app allow selecting source and destination as the same folder later, or always prevent it?
- Should advanced MozJPEG settings be hidden forever, or added under an expert section?
