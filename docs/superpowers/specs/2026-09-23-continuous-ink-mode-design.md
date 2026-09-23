# Continuous PDF Viewer — Ink Mode Support

Date: 2026-09-23
Status: Approved design, pending implementation plan

## Problem

Ink (pen markup) is only partly supported in the continuous-scroll PDF viewer
(`ContinuousReferencePdfPane`, hosted by `UnifiedReferenceViewer`):

- Strokes load, save and display for the **centered page only**
  (`markupStrokesForPage` matches `filename == resolvedPdfFilename && page == sourcePage`).
  Strokes on every other page are invisible while scrolling.
- Turning ink on **disables all scroll and zoom gestures**
  (`gesturesEnabled = !markupEnabled`), pinning the list to one page.

## Goal

In continuous mode, with ink on:

1. The stylus draws on **any visible page**. A stroke belongs to the page whose overlay received it.
2. Strokes for **every page** are shown while scrolling.
3. **Fingers scroll, pinch-zoom and tap** as normal; the pen draws.
4. Existing behaviour is preserved where a finger owns ink: "allow finger drawing" on, or eraser tool active. There the list stays locked, as today.

Paged mode is unchanged.

## Non-goals

- No changes to `PdfMarkupOverlay` or its stroke geometry/coordinate math. Zoom is a `graphicsLayer` scale
  on the list, and Compose maps pointer coordinates back through it, so strokes land in unzoomed page space.
- No changes to the on-disk markup format or `PdfMarkupStore` API.
- No lazy/per-visible-page loading (job markup files are small).

## Design

### 1. Per-page state — `PdfMarkupPageStates`

New small holder (own file under `ui/markup/`), replacing the single-page
`localMarkupStrokes` / `localDeletedIds` in `UnifiedReferenceViewer`.

- Keyed by `PdfMarkupPageKey(filename, sourcePage)` (the existing store key type).
- Snapshot-backed so composition observes changes.
- API: `strokesFor(key)`, `add(key, stroke)`, `erase(key, strokeId)`, `undoLast(fallbackKey)`,
  `replaceAll(map)` (used by load), `hasUndo`.
- Each `add`/`erase`/undo persists **only its own page** via `PdfMarkupStore.savePageMarkup`.
  IO stays in the viewer's coroutine scope (`Dispatchers.IO`), as today.
- Persist semantics per page match today's `persistMarkupState`: save non-deleted strokes plus the deleted-id list.

**Loading.** One `LaunchedEffect` keyed on `(pdfMarkupStore, pdfMarkupJobFolderName, markupChangeGeneration)`
calls `getMergedActiveStrokesByPage(jobFolderName)` on IO and `replaceAll`s the holder. Deleted ids per page come from
`loadTabletPageMarkup` for pages present in the map. Syncthing-driven changes still reload via `markupChangeGeneration`.
The effect no longer depends on `resolvedPdfFilename` / `sourcePage`.

**Undo.** Session stack of `(key, strokeId)`, most recent first. If the stack is empty, fall back to the last
non-deleted stroke on the centered page (today's behaviour). Undo marks the stroke deleted and persists that page.

**Call sites.**
- Continuous: `markupStrokesForPage = { filename, page -> if (markupStrokesVisible) states.strokesFor(key(filename, page)) else emptyList() }`;
  add/erase callbacks route by the `(filename, page)` the pane already passes.
- Paged: `markupStrokes = if (markupStrokesVisible) states.strokesFor(currentKey) else emptyList()`; callbacks route to `currentKey`.
- `hasMarkupHistory` becomes `states.hasUndo` (any visible stroke on the centered page or in the session stack).

### 2. Gestures — `ContinuousReferencePdfPane`

- `gesturesEnabled = !(markupEnabled && fingerOwnsInk)` where
  `fingerOwnsInk = allowFingerDrawing || activeTool == ERASER`.
  Extracted as a pure, tested function
  `shouldContinuousPaneOwnFingerGestures(markupEnabled, allowFingerDrawing, activeTool)`.
  The pane needs `markupToolState` (already passed) to compute it.
- The scroll/zoom handler **ignores stylus pointers**: after `awaitFirstDown`, if `pointerType` is `Stylus` or
  `Eraser`, skip the gesture entirely (no pan, zoom, fling or tap-to-toggle-chrome) and wait for that pointer to lift.
  Extracted as a small predicate for testing.
- If a stylus goes down during an in-progress finger gesture, abort the finger gesture (palm rejection backstop).
- Overlay presence stays `markupEnabled || strokes.isNotEmpty()` per page. `PdfMarkupOverlay` already returns `false`
  for finger `ACTION_DOWN` when finger drawing is off, so finger events fall through to the parent handler; no overlay edits.
- The centered-page gate on strokes is removed; drawing is no longer pinned to the current page.

### 3. Errors and edge cases

- Store null, blank job folder, blank filename or `sourcePage <= 0`: holder is empty, `add`/`erase` are no-ops, nothing persists (matches today's guards).
- Store `readOnly`: `savePageMarkup` already no-ops; in-memory state still updates for the session, as today.
- Stroke that ends outside its page: ends on the overlay's `ACTION_UP` and clamps, as today.

## Testing

Unit (JVM):
- `PdfMarkupPageStates`: add/erase per page, undo stack across pages, undo fallback to centered page, `replaceAll`,
  per-page persist callback invoked with the right key and payload, blank/invalid key guards.
- `shouldContinuousPaneOwnFingerGestures`: full truth table.
- Stylus-skip predicate.
- Existing `PdfMarkupSupportTest` / `PdfMarkupStoreTest` must stay green. One known off-device `MotionEvent` stub failure is env-only, not a regression.

Manual on tablet (release build, per project convention):
- Draw on page N, scroll, draw on page N+1; both persist across leaving and re-entering the viewer.
- Strokes on all pages visible while scrolling with ink on and off.
- Finger scroll and pinch work with ink on; pen strokes do not scroll the list.
- Palm resting during a stroke does not scroll.
- Eraser tool and "allow finger drawing" lock the list as before.
- Undo after switching pages undoes the most recent stroke.
- Pinch-zoomed drawing lands where the pen touches.

## Working-tree note

Files this touches (`UnifiedReferenceViewer.kt`, `ContinuousReferencePdfPane.kt`) or that neighbour it have
uncommitted edits from other work. Implementation builds on top of them without reverting anything, and commits stage only files this change touches.
