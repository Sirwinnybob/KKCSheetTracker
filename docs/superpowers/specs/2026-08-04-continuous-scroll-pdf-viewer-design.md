# Continuous Scroll for Reference PDF Viewers — Design

**Date:** 2026-08-04

## Problem

Reference PDF viewers (Assembly Sheets, Plans & Elevations, Cover Sheet) show one page at a time with prev/next arrows. Users want a Word/Samsung-Notes-style continuous scroll option: all pages stacked vertically, or — when a viewer is in split mode — a horizontal strip per pane. This must not come at the cost of battery life, since the app runs on shop tablets all day.

Out of scope: the CNC Sheet Viewer (`ui/viewer/SheetViewerScreen.kt`) is a separate renderer and is not touched by this change.

Related prior work: [2026-08-04-pdf-viewer-battery-optimization-design.md](2026-08-04-pdf-viewer-battery-optimization-design.md) landed the same day — event-driven markup refresh (no more 1s polling), visible-pane-only composition in split fullscreen, reduced-scale adjacent-page rendering, and disciplined `PdfRenderer`/`ParcelFileDescriptor` closing. This design builds on those same principles rather than re-deriving them.

## Scope

Applies everywhere `ReferencePdfPane` / `UnifiedReferenceViewer` is used today:
- `ui/viewer/ReferencePdfViewerScreen.kt` (Assembly Sheets, Plans & Elevations, Cover Sheet)
- `ui/assembly/AssemblyViewerScreen.kt` split-mode panes

Direction:
- **Single/fullscreen mode** → vertical stack (`LazyColumn`)
- **Split mode** (`AssemblyViewLayout.SPLIT`) → horizontal strip (`LazyRow`) independently per pane, matching each pane's existing orientation

## Toggle & persistence

- **Settings screen**: new "Continuous scroll" switch, default **OFF**. Follows the existing switch-setting pattern in `ui/settings/SettingsScreen.kt`. Stored in the `kkc_ui_prefs` SharedPreferences file (same file `ReferencePdfViewerScreen.kt` already uses for page-resume).
- **In-viewer quick toggle**: a button in the top nav-bar controls, next to the markup pen toggle. Flips mode for the current viewing session only — does **not** persist. Leaving and reopening the screen reverts to the Settings default.
- New state `continuousScrollEnabled: Boolean`, threaded the same way `markupEnabled` is today: owned in `ReferencePdfViewerScreen`, passed into `UnifiedReferenceViewer`, which picks between `ReferencePdfPane` (existing) and the new `ContinuousReferencePdfPane`.

## Architecture

New sibling component, `ui/components/ContinuousReferencePdfPane.kt`, rather than extending the existing 1192-line `ReferencePdfPane.kt` in place or adopting a third-party PDF library:

- Reuses `PdfRenderEngine`, `ZoomablePdfImage`, and `PdfMarkupOverlay` as-is.
- Keeps the existing single-page viewer completely untouched — zero regression risk to the working today's-behavior path.
- Avoids rebuilding markup coordinate mapping and dark-mode matte handling against a new dependency.

`UnifiedReferenceViewer.kt` selects between the two:
```
if (continuousScrollEnabled) ContinuousReferencePdfPane(...)
else ReferencePdfPane(...)
```

## Rendering strategy (battery/perf)

- **Virtualization is the primary battery win.** `LazyColumn`/`LazyRow` only compose items near the viewport; scrolled-off pages are fully disposed and their bitmaps become GC-eligible immediately — no manual eviction bookkeeping needed beyond what Compose already does.
- **Upfront aspect-ratio pass, no bitmaps.** Before showing the list, call `PdfRenderEngine.pageAspectRatio()` per page (metadata only) to size list items correctly. Avoids layout jank and gives the scrollbar accurate proportional positions.
- **Settle-only rendering.** Reuse the debounce pattern already proven in `ReferencePdfPane.kt` (`debounce(120)` + `distinctUntilChanged` around the zoom-detail-tile flow). A page only renders its bitmap once it is inside the render window AND the list is not actively flinging or being dragged via the scrollbar. Fast-scrolling past a page renders nothing for it.
- **Small render window.** Visible items + 1 page buffer per side — not the whole document. Uses the same `renderBasePage()` call as today (already downsampled to view-size × ~1.1, not full print resolution).
- **Single-flight rendering per file.** `PdfRenderEngine`'s existing `Mutex` already serializes page open/render/close on one `PdfRenderer` instance, naturally capping concurrent render work per file without new throttling code.
- **Per-page pinch-zoom unchanged.** Each list item wraps the same `ZoomablePdfImage` used today. Because list items are disposed when scrolled out of the window, zoom state resets to fit automatically when a page re-enters — no extra state management needed.
- **Markup drawing locks scroll.** When markup drawing is enabled, the pane's own scroll/drag gesture is disabled (same `allowFingerGestures` gate used today), so the pen doesn't fight the list — effectively pins to the current page while drawing, matching single-page mode's feel.

## Multi-file page resolution (virtual mapping)

Assembly Sheets can virtually stitch pages from more than one underlying PDF (FF/FL variant switching — `UnifiedVirtualPageMapping` in `UnifiedReferenceViewer.kt:74-78`). Today's single-page pane only ever holds one `PdfRenderEngine` because only one page is ever visible. Continuous mode needs several pages resolved at once, potentially from different files.

`ContinuousReferencePdfPane` accepts a per-page resolver `(displayPage: Int) -> Pair<File, sourcePage: Int>?` instead of a single `pdfFile`, backed by a small engine cache keyed by file path (cap ~3 open `PdfRenderEngine`s, LRU-closed) instead of one engine.

In practice this is usually a non-issue: a real job's `cabinet_sheet_index.json` (e.g. `368 - KATRINA 3484 MARILLA`) shows a single `pdfFilename` across the whole assembly document for the common case, and Plans & Elevations / Cover Sheet never use virtual mapping at all. The cache exists for the jobs that do split FF/FL across files.

## Segmented label scrollbar

- New component `PdfLabelScrollbar`, docked on the trailing (right) edge, full pane height — clear of the existing `TopEnd` page-nav pill (`ReferencePdfPane.kt`, recently repositioned from `BottomEnd` to `TopEnd`) regardless of whether the markup toggle is present.
- Segments are built from the same label logic the Sheet Navigator already uses (`defaultNavigatorPrimaryLabel`, `NavigatorRowModel` in `UnifiedReferenceViewer.kt:286-314`) — "Cabinet 12", "ROOM - PLAN VIEW", etc. No duplicated labeling logic.
- `ReferenceDocType.DELIVERY_SHEETS` (Cover Sheet) has no cabinet/room mapping, so it falls back to plain "Page N" labels.
- Tap or drag the scrollbar to jump. A floating label callout shows the page under your finger while dragging (free — pure label lookup, no PDF I/O) and the list position updates live, but **actual page bitmap rendering follows the same settle-only rule** — nothing renders until the drag stops or the tap lands.

## Position tracking & resume

- `LazyListState`'s first-visible-item index becomes the new `currentPage`/`displayPage`, wired through the same `onDisplayPageChange` / `currentPage` resume-key mechanism `ReferencePdfViewerScreen.kt` already uses — page-resume-on-reopen keeps working unchanged.
- Markup strokes for the page nearest the center of the viewport load/save exactly as today (per-page, keyed by resolved filename + source page), just triggered by "which page is centered" instead of "which page is current."
- Switching mode mid-session (single ↔ continuous) preserves position: continuous mode opens scrolled to the current page; switching back to single-page mode lands on whatever page was centered.

## Testing

- Unit tests for: scrollbar segment building (including the Cover Sheet plain-page-number fallback), render-window/settle-debounce logic, and the multi-file page resolver / engine cache eviction.
- Manual verification on a shop tablet: scroll a large Assembly Sheets doc continuously and confirm no dropped frames, bitmap memory stays bounded (no growth over a long scroll session), and battery draw is comparable to single-page mode over an equivalent viewing session.
