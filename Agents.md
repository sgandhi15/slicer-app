# Slicer Project Development Rules

- Keep Kotlin source files between **400–500 lines**. Treat this as the hard upper bound and split files before they exceed this range.
- Group code by responsibility:
  - `SlicerModels.kt` keeps app-wide constants and shared data structures.
  - Processing/IO/metadata/compression logic should be split into focused files.
  - UI should be split into small composables by screen region (`header`, `setup`, `compare`, `results`, `content`).
- Use `internal` visibility for cross-file module access when needed; avoid `private` helpers when future refactors may split files.
- Remove dead code as soon as it is identified:
  - Delete no-op stubs and always-return constants/functions.
  - Remove unused functions/values during the same cleanup pass.
- Prefer composition over giant functions:
  - If a composable file has many responsibilities, move related blocks to smaller composables.
  - If a processing file has mixed concerns (IO + compression + metadata), split into dedicated modules.
- Avoid broad churn:
  - Preserve existing behavior and naming unless refactor requires changes.
  - Keep behavior-driven tests/verification commands in one pass after refactor.
