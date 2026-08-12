# CNC Sheet Inversion Design

## Goal

Make CNC sheet previews comfortable to read in dark mode without creating or storing alternate PDF files.

## Behavior

The displayed CNC sheet bitmap is inverted when either condition is true:

- The idle-power-save phase is `DIMMED` or `SYNC_PAUSED`.
- The active app theme is dark and the user has disabled **Use standard sheets**.

The bitmap remains unchanged for an active light theme or when **Use standard sheets** is enabled. The rule applies equally to the full PDF-page bitmap and the extracted diagram bitmap.

## Implementation

`SheetViewerScreen` will derive one `invertSheetBitmap` value from the existing idle phase, the supplied theme state, and the standard-sheets preference. It will pass that value to the two image-only viewers. Those viewers will apply an invert color matrix to their `Image` composable, after existing zoom/pan transforms.

This leaves viewer controls, selection/highlight overlays, and markup strokes in their normal colors. It also avoids additional PDF rendering, bitmap copies, cache variants, or external dependencies.

## Verification

Unit tests will cover the inversion decision for the timeout, dark-theme preference, standard-sheets opt-out, and the normal light rendering case. The focused JVM test class and debug APK build will be run after the implementation.
