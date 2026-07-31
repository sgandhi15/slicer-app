# One-Time Guided Onboarding TODO

## Goal

Teach the real compression workflow inside the normal app interface, without adding extra feature slides or forcing unnecessary actions.

## Tutorial Flow

- [ ] After the welcome screen, highlight **Select images** with: `Choose one or more JPEG images.`
- [ ] After images are selected, highlight **Quality** with: `Balanced works well for most images.`
- [ ] When compression becomes available, highlight **Start Compression**.
- [ ] On the first results screen, highlight a result with: `Tap an image to compare before and after.`
- [ ] Mark the tutorial complete after the results hint is shown or the user skips it.

## Behavior

- [ ] Add a visible **Skip tutorial** action.
- [ ] Use small hints anchored to real controls instead of blocking full-screen overlays.
- [ ] Never require the user to change quality or output folder.
- [ ] Allow one or many selected images; do not force a single-image demo.
- [ ] Do not add permission prompts or a bundled sample image.
- [ ] Persist tutorial progress so completed hints are not repeated.
- [ ] Recover safely if the app closes midway through the tutorial.

## Implementation Plan

- [ ] Define tutorial steps and persisted progress state.
- [ ] Add stable anchors/test tags to each highlighted control.
- [ ] Build one reusable anchored hint component.
- [ ] Advance steps from existing user actions rather than separate tutorial buttons.
- [ ] Add Compose UI tests for advancing, skipping, persistence, and returning to the app.
- [ ] Check hint placement on compact screens and with large font sizes.

## Product Guardrails

- Keep the tutorial optional, brief, and shown only once.
- Keep normal controls usable while hints are visible.
- Explain actions only when they become relevant.
- Do not repeat privacy messaging already covered by the welcome screen.
