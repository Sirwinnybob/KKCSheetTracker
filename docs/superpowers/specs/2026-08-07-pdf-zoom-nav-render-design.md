# PDF Zoom Limit + Fast-Nav Low-Res Render

**Date:** 2026-08-07
**Status:** Approved design

## Goal

Two related PDF viewer improvements, both touching `ReferencePdfPane.kt`
(paged mode) and `ContinuousReferencePdfPane.kt` (continuous-scroll mode):

1. Raise max pinch-zoom from 10x/14x to 20x in both viewers.
2. Stop firing a full-resolution page render for every page landed on
   during fast navigation (continuous-scroll fling, or rapid paged
   next/prev taps) — show a cheap thumbnail immediately and defer the
   full render until navigation settles.

## Scope

### In scope

- `ContinuousReferencePdfPane.kt:82` — `CONTINUOUS_MAX_ZOOM` 10f → 20f.
- `ReferencePdfPane.kt:889` — `maxZoom` local val 14f → 20f.
- `ReferencePdfPane.kt:188` — render-side clamp `viewport.zoom.coerceIn(1f, 14f)`
  → `coerceIn(1f, 20f)`. This clamp is separate from the gesture-level
  `maxZoom` above; missing this leaves the UI able to request 20x while
  the bitmap render silently caps at 14x.
- `minZoom` unchanged (1f) in both viewers.
- Paged mode (`ReferencePdfPane.kt`): gate the existing `renderBasePage`
  call (currently fired immediately in the `LaunchedEffect` keyed on
  `currentPage`, `ReferencePdfPane.kt:505`) behind a `debounce(120)` on
  `currentPage`, mirroring the debounce already used for the zoom detail
  tile at `ReferencePdfPane.kt:539`.
- Continuous mode (`ContinuousReferencePdfPane.kt`): gate the existing
  per-page `renderBasePage` call (`ContinuousReferencePdfPane.kt:530`)
  behind the same settle signal (`isInteracting`/`isFlinging`) already
  used to gate the zoom crop tile at `ContinuousReferencePdfPane.kt:431`.
- Both viewers: show `renderThumbnail()` (existing function, already used
  in `PdfLabelScrollbar.kt`, `CoverPageOverlay.kt`, `JobBoardGrid.kt`) as
  an immediate placeholder for any page whose full base render hasn't
  landed yet.
- A small per-page LRU thumbnail cache, same shape as the existing
  `basePageCache` in `ReferencePdfPane.kt`, so a revisited page shows its
  thumbnail instantly instead of re-decoding.

### Out of scope

- No change to the zoom detail-tile render path (`renderViewportTile` /
  `renderCropFraction`) — already debounced, already crop-renders from
  vector source so image quality holds at 20x without changes.
- No change to `minZoom` (stays 1x both viewers).
- No adaptive/velocity-scaled resolution (rejected approach B) — this is
  a two-tier system: thumbnail placeholder, then full base render.
- No eager background prefetch of neighboring-page thumbnails (rejected
  approach C) — thumbnails render on demand when a page is first entered,
  same trigger point as today's base render.
- No changes to double-tap-to-zoom (none exists today) or to any external
  caller — `minZoom`/`maxZoom` are not exposed as composable parameters
  in either file, so no call sites need updating.

## Architecture and data flow

### Zoom limit

Pure constant changes. Both viewers already crop-render the visible
region from the PDF's vector source at viewport resolution on every zoom
change (`renderViewportTile` / `renderCropFraction`), rather than
bitmap-scaling a fixed-resolution render. Raising the ceiling to 20x does
not introduce blur — the existing `maxArea` bitmap-size clamp (12–16
megapixels) is well below what a typical tablet viewport needs at 20x on
a cropped region.

### Fast-nav low-res render

Today, both viewers already split rendering into two tiers on zoom/pan:
an instant cheap "base" render (fit-to-box, cached) plus a `debounce(120)`
sharp crop tile once the gesture settles. That pattern is proven and
reused here — the gap is that the *base* render itself has no settle gate
on page-to-page navigation, so flinging through N pages or tapping
next/prev rapidly fires N full `renderBasePage` calls serialized through
one mutex per `PdfRenderEngine`.

Change: split page-entry rendering into the same two tiers used for
zoom — `renderThumbnail()` fires immediately (cheap, already-proven
function, ~340px default width) and is shown as soon as it lands.
`renderBasePage()` fires only after the settle signal:

- **Paged mode**: `currentPage` changes flow through `debounce(120)`
  before triggering `renderBasePage`, identical in shape to the existing
  debounce on `viewportState`/`isInteracting` at line 539. The thumbnail
  effect has no debounce — it fires on every `currentPage` change,
  same as today's aspect-ratio lookup at line 495.
- **Continuous mode**: the existing `renderBasePage` `LaunchedEffect`
  (line 530), keyed on `displayPage`/`inWindow`, gains the same
  `settled` condition already computed for the crop tile at line 431
  (derived from `isInteracting`, `listState.isScrollInProgress`,
  `isFlinging`). The thumbnail render is unconditional on `inWindow`,
  same trigger as today's base render.

Once the full base bitmap lands, it replaces the thumbnail in the same
`baseBitmap` state slot both viewers already use — no new UI layer,
`PageBitmapLayers` (continuous) and the direct `Image`/`Bitmap` draw
(paged) already draw whatever `baseBitmap` currently holds.

Thumbnail results are cached per page (LRU, matching `basePageCache`'s
existing shape) so re-entering an already-thumbnailed page during a
fast fling shows the thumbnail with zero decode latency.

## Error handling

No new failure modes. A failed thumbnail render simply leaves the
placeholder blank (matte color) a beat longer — the existing
`PdfRenderUiState.Error` path for a failed base/detail render is
unchanged.

## Testing

No new unit-testable logic — this is Compose timing/debounce behavior,
verified by hand:

- Pinch-zoom to confirm 20x is reachable and image stays sharp (both
  viewers).
- Fast-fling the continuous-scroll list across many pages: confirm no
  per-page full-res render stall, confirm full render lands within
  ~150ms of the fling settling.
- Rapid-tap next/prev in paged mode: confirm the same — thumbnail shows
  immediately per tap, full render only after taps stop for ~120ms.
- Revisit an already-seen page during fast nav: confirm thumbnail shows
  instantly from cache, no re-decode flicker.
