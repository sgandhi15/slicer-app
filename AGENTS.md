# Slicer Project Guidance

## Visual Baseline

Slicer uses a restrained, warm light theme. Keep interfaces predominantly neutral and use burnt orange only to communicate a primary action, active control, selection, progress, or a small interactive link.

The Compose theme in `app/src/main/java/com/example/slicer/ui/theme/` is the source of truth. Use `MaterialTheme.colorScheme` in UI code instead of adding inline color values.

Core palette:

- Primary: `#C2410C`
- Primary container: `#FFEDD5`
- On primary container: `#7C2D12`
- Background: `#FCFBF9`
- Surface: `#FFFFFF`
- Surface variant: `#F7F3EF`
- Primary text: `#1C1917`
- Secondary text: `#6B6460`
- Outline: `#D6D0CB`
- Error: `#B91C1C`

Keep color usage minimal:

- Prefer one solid primary action per screen.
- Use pale orange for selected states, badges, and subtle highlights.
- Keep ordinary cards, headings, borders, and secondary actions neutral.
- Do not use orange for errors or warnings.
- Do not introduce gradients, extra accent colors, or new semantic colors unless a concrete UI state requires them.
- Maintain accessible contrast, including white content on the primary orange.

## Code Organization

- Keep Kotlin files focused and preferably below 500 lines. Split files by responsibility before they become difficult to navigate.
- Keep app-wide constants and shared data structures in `core/Models.kt` or another focused file under `core/`.
- Separate processing, storage I/O, metadata, and compression logic into focused files.
- Split UI into small composables organized by screen or screen region, such as setup, quality, comparison, processing, and results.
- Prefer small, composable functions over files or functions that coordinate unrelated responsibilities.

## Kotlin Conventions

- Use the narrowest practical visibility. Keep file-local implementation details `private` and use `internal` for declarations shared across the app module.
- Remove dead code, no-op stubs, unused declarations, and permanently constant branches when they are identified.
- Preserve existing behavior and naming unless the requested change or a necessary refactor requires otherwise.
- Avoid broad cleanup churn unrelated to the current task.
- Run relevant behavior-driven tests and verification commands after refactoring.
