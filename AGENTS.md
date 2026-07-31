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
