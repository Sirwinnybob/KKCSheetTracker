# Crown SVG Previews Dark Mode Specification

## Summary
Add theme-aware dark mode rendering to crown/molding SVG profile previews across the application. When dark mode is active and "Use standard sheets" is disabled, black profile lines in crown SVGs will invert to white on a solid black background. If "Use standard sheets" is enabled, crown previews remain in light mode (white background, original dark lines).

## Requirements & Scope
1. **Target Previews**:
   - Standards Molding Library grid cards (`MoldingCard` in `MoldingListScreen`).
   - Standards Molding Library detail overlay (`MoldingDetailOverlay`).
   - Hardwoods Rip Cut List detail overlay (`MoldingDetailOverlay` launched from `HardwoodsWorkspaceScreen`).

2. **Trigger Condition**:
   - Previews render in **Dark Mode** (`#000000` background, `ColorFilter.tint(Color.White)`) if and only if `isDarkTheme == true` **AND** `useStandardSheets == false`.
   - Previews render in **Light Mode** (`#FFFFFF` background, no tint color filter) if `isDarkTheme == false` **OR** `useStandardSheets == true`.

3. **Non-Goals**:
   - Modifying physical `.svg` files on disk.
   - Changing PDF drawing viewer light/dark behavior beyond existing `useStandardSheets` integration.

## Architecture & Data Flow
- `isDarkTheme` and `useStandardSheets` states flow from `MainActivity` / `NavGraph` into feature tab hosts.
- `NavGraph.kt` passes `isDarkTheme` and `useStandardSheets` (or calculated `isDarkPreview = isDarkTheme && !useStandardSheets`) to `MoldingListScreen` and `HardwoodsWorkspaceScreen`.
- `MoldingCard` and `MoldingDetailOverlay` accept `isDarkPreview: Boolean`:
  ```kotlin
  val previewBgColor = if (isDarkPreview) Color.Black else Color.White
  val imageColorFilter = if (isDarkPreview) ColorFilter.tint(Color.White) else null
  ```

## Decision Log
1. **Scope**: Applied across all crown/molding SVG preview entry points (Standards Grid, Standards Detail, Hardwoods Rip Cut Detail) for visual consistency.
2. **Standard Sheets Rule**: Respects `useStandardSheets` preference so users who prefer light background technical line drawings retain light mode crown previews in dark mode.
3. **Parameter Flow**: Explicit parameter passing (`isDarkPreview`) rather than `CompositionLocal` indirection.
