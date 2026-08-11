# Continuous PDF Pinch and Chrome Design

## Goal

Make continuous PDF mode keep the document point under the pinch centroid in both axes, allow two-finger main-axis panning while zooming, and keep the PDF rendered beneath its header and right scrollbar.

## Scope

- Continuous PDF mode only; paged PDF behavior is unchanged.
- Vertical continuous mode is the reported defect. Horizontal mode must continue to use the same shared gesture semantics.
- No new dependencies, services, or device configuration.

## Root Cause

`ContinuousReferencePdfPane` applies the document's shared zoom with a center-pivoted `graphicsLayer`. Its transform helper produces the main-axis compensation needed to preserve an off-center pinch, but continuous mode retains only the cross-axis pan. It also intentionally suppresses main-axis list scrolling while more than one pointer is down. Consequently, vertical pinch zoom uses the pane center and cannot simultaneously pan the `LazyColumn`.

`ReferencePdfViewerScreen` applies `Scaffold`'s top-app-bar inset to `UnifiedReferenceViewer` even when the app bar is transparent. The continuous viewer also lays out its document-controls row above a reduced-size PDF pane and the pane reserves permanent right padding for the scrollbar. The PDF therefore cannot render under the hideable top app bar, the in-pane controls, or the scrollbar.

## Design

### Gesture coordination

Keep the single gesture owner in `ContinuousReferencePdfPane`; it is responsible for pointer arbitration, zoom, cross-axis pan, list scrolling, and flings. Extend it so every transform frame converts the helper's main-axis focal-point compensation plus its two-finger pan delta into a list-space scroll delta. Use the existing clamped edge-overscroll behavior when the list reaches either document boundary.

The resulting transform preserves the document coordinate under the current centroid for both vertical and horizontal orientations. Cross-axis panning stays bounded by `maxCrossAxisPan`; multi-touch release continues to suppress fling velocity.

### Stable viewport and overlay chrome

For continuous mode, do not apply the screen `Scaffold` top-app-bar inset to `UnifiedReferenceViewer`; the transparent/fading app bar then overlays the full document instead of reserving an empty band. Keep the inset for paged mode. Inside the continuous branch, use a full-size `Box` as the PDF viewport. `ContinuousReferencePdfPane` fills that box with no permanent scrollbar-end padding. The document-controls row and `PdfLabelScrollbar` are overlay siblings above the PDF. Chrome remains interactive when visible, and changing its visibility changes only overlays, not the PDF layout size.

The scrollbar continues to reserve only its interaction footprint through its own layout and remains rendered above the document. Existing haze-source wiring stays on the PDF layer so the scrollbar can blur live document content beneath its expanded panel.

### Tests and verification

Add focused JVM tests beside the continuous-pane tests for:

- An off-center vertical pinch producing the main-axis list compensation needed to keep the source position under the centroid.
- A multi-touch transform including main-axis pan producing a list scroll delta rather than dropping it.
- Bounds and zoom clamping continuing to apply to the compensation.

Use a small pure helper for the main-axis delta if needed, keeping Compose gesture plumbing thin and testable. Run the focused test class, the relevant viewer/component test suite, and `assembleDebug`.

## Acceptance Criteria

- In vertical continuous mode, pinching near the top or bottom zooms around the fingers rather than the screen center.
- Two fingers can move vertically while pinching, and the document follows without a jump when the gesture ends.
- Cross-axis panning remains available and bounded while zoomed.
- In continuous mode, the PDF stays full-viewport behind the screen top app bar, document controls, and right scrollbar; hiding or showing the app bar does not resize the PDF viewport.
- Existing continuous-mode scroll, page tracking, scrollbar navigation, markup-mode gesture exclusion, and no-multi-touch-fling behaviors remain intact.
