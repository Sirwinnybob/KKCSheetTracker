# Scrollbar Label-Only Bubble

## Context

`PdfLabelScrollbar` ([PdfLabelScrollbar.kt](../../../app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt)) currently shows a floating carousel while dragging: up to 5 slots (touched page + 2 shrinking neighbors each side) with decoded PDF-page thumbnails plus a label under the main/±1 slots. This adds an alternative display mode — inspired by the classic [Android Contacts fast-scroller bubble](https://ekeitho.medium.com/building-google-contacts-screen-and-its-scrolling-bubble-feature-in-compose-1f8b7e292df) — that shows only the current entry's text label in a single floating pill, no thumbnails, no neighbor slots.

This is an independent style preference, not a performance fallback — it's selectable regardless of device tier, alongside (not replacing) the existing thumbnail carousel and the separate Low-end device mode setting.

## Setting

New preference in `uiPreferencesStore`: `getScrollPreviewLabelOnly(): Boolean` / `setScrollPreviewLabelOnly(Boolean)`, default `false`. Exposed as a `Switch` in the **Appearance** card in [SettingsScreen.kt](../../../app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt) ("Label-only scroll preview"), following the same `remember { mutableStateOf(...) }` pattern already used by the Performance card's switches. Existing users default to the thumbnail carousel (unchanged behavior); the new mode is opt-in.

Wired via a new `LocalScrollPreviewMode` `CompositionLocal` (`ScrollPreviewModeCompositionLocal.kt`), following the exact precedent of `LocalLowEndMode` ([LowEndModeCompositionLocal.kt](../../../app/src/main/java/com/kkc/sheettracker/ui/components/LowEndModeCompositionLocal.kt)): computed once in `MainActivity` from the `UiPreferencesStore` value and provided via `CompositionLocalProvider` alongside the existing `LocalLowEndMode` provider, read directly inside `PdfLabelScrollbar` with `.current` — no explicit parameter threading through `UnifiedReferenceViewer` or any of its 6 call sites. `PdfLabelScrollbar` is the only caller of the composable that needs this value.

### Forced in split view

`UnifiedReferenceViewer` already has an `isSplitPaneActive: Boolean = false` param (used today to flip the continuous-scroll pane's orientation) that reaches `AssemblyViewerScreen`'s split-view call sites. `PdfLabelScrollbar` gains the same `isSplitPaneActive: Boolean = false` param, threaded the same way, and computes `val effectiveLabelOnly = labelOnlyMode || isSplitPaneActive`. Split view forces the bubble regardless of the Appearance setting — the narrow per-pane width can't comfortably fit the 240dp thumbnail carousel. The Appearance setting remains fully user-controlled in normal (non-split) single-pane view.

## Component

New composable `ScrollLabelBubble(entry: ScrollbarEntry, touchYPx: Float, trackHeightPx: Float, hazeState: HazeState?, modifier: Modifier)` in the same file.

At the existing carousel call site, branch:

```kotlin
val effectiveLabelOnly = labelOnlyMode || isSplitPaneActive
if (effectiveLabelOnly) {
    ScrollLabelBubble(entries[focusIndex], touchYPx, trackHeightPx, hazeState, ...)
} else {
    // existing carouselSlots / fittedSlots Column, unchanged
}
```

`carouselSlots`, `thumbCache`, `PdfEngineCache`, and the thumbnail-loading `LaunchedEffect` all stay conditional on `!effectiveLabelOnly` — in label mode (from the setting, or forced by split view), no PDF page decoding happens at all.

`entries[focusIndex]` (already computed for the existing carousel) supplies `label`, `rangeLabel`, and `page` directly — no new lookup path.

## Visual design

- **Shape:** rounded-rect pill (not a teardrop) — sheet labels can be long (multiple cab numbers, room names), so a dynamically-sized rect fits better than a fixed circle/teardrop.
- **Frosted glass:** follows the project's established pattern (see [CLAUDE.md](../../../CLAUDE.md) "Frosted Glass Buttons"): `shadow(elevation, shape, clip = false)` → `clip(RoundedCornerShape(10.dp))` → `hazeEffect(hazeState, style = HazeDefaults.style(backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), blurRadius = 14.dp))`. Explicitly not `Surface(shadowElevation = ...)` with a semi-transparent color, which causes shadow bleed-through.
- **Width:** `widthIn(max = carouselWidth - carouselPadding - carouselEndPadding)` (same cap as today's carousel), intrinsic width otherwise — grows with short labels, caps and ellipsizes long ones.
- **Content:** two lines.
  - Primary: `entry.label`, single line, `TextOverflow.Ellipsis`, sized larger than the current carousel's label text (`titleMedium`-ish) since it's now the sole content.
  - Subtitle: `entry.rangeLabel ?: "Sheet ${entry.page}"`, smaller, same ellipsis treatment.
- **Position:** reuses the existing `touchYPx`-clamped vertical placement logic (`carouselTopPx`), centered on the bubble's own (near-fixed, two-line) height instead of the main-tier thumbnail height.
- **Entrance/exit:** reuses the carousel's existing slide+fade `AnimatedVisibility` treatment (introduced in commit `6c657ad`) so drag start/stop feels consistent between both modes.
- **Label swap during drag:** instant, no per-entry animation — only the bubble container's own entrance/exit animates. Keeps rapid dragging responsive.

## Edge cases

- **Plan-view pages:** `entry.label` already carries the plan-view label text (`ScrollbarEntry` construction is unchanged); subtitle falls back to `"Sheet ${entry.page}"`. No special-casing needed.
- **Bucketed display mode** (large jobs): `entry.rangeLabel` is already populated by the existing bucketing logic — subtitle shows the page range automatically.
- **Setting toggled mid-session:** takes effect on the next drag gesture, no restart required (plain remembered state, same as Performance card switches).
- **Interaction with Low-end mode:** none — orthogonal setting. Label mode incidentally does less work than the thumbnail carousel (no decode), but is not gated by or tied to low-end mode.
- **Split view:** bubble is forced on (`isSplitPaneActive`) independent of the Appearance setting's value — even if the user has the thumbnail carousel selected for normal viewing, split view always shows the bubble. Leaving split view (closing one pane / returning to single-pane) reverts to whatever the setting says.

## Testing

No unit tests exist for this file today; verification is manual on-device:

1. Toggle "Label-only scroll preview" on in Settings → Appearance.
2. Drag the scrollbar on a job with a long cab-number list — confirm the primary line ellipsizes at the cap width instead of overflowing.
3. Drag across a plan-view page — confirm the plan label and "Sheet N" subtitle show correctly.
4. Drag on a large job in bucketed mode — confirm the subtitle shows the page range instead of a single page number.
5. Toggle the setting off mid-session (without restarting) and drag again — confirm it reverts to the thumbnail carousel.
6. Confirm the bubble's frosted background has no shadow bleed-through (the known `Surface(shadowElevation)` + alpha bug this design deliberately avoids).
7. With the Appearance setting OFF (thumbnail carousel selected), open a split view (Assembly or Specialty job → Split View) — confirm the bubble shows anyway in both panes, not the carousel.
8. Return from split view to single-pane — confirm the carousel comes back (since the setting is still OFF).
