# Continuous-Scroll Scrollbar: Full-Height Track + Floating Carousel Preview

## Goal

Follow-up to the LazyListState unification (`docs/superpowers/specs/2026-08-05-continuous-scrollbar-lazy-unification-design.md`, implemented in `PdfLabelScrollbar.kt`). On-device testing after that change surfaced two new, related problems with the idle track itself — not the pill-vs-preview mismatch that unification fixed:

1. **Drag precision was broken.** A screenshot of the idle scrollbar showed the tick column only occupying roughly the top 15% of the screen — the rest of the reserved track height was empty. Root cause: `verticalArrangement = Arrangement.spacedBy(0.dp)` packs the (very short, 2dp-tall) idle ticks tightly at the top of the `LazyColumn` instead of spreading them across the full available height. Since `hitTest` maps touch Y directly against real row centers, a touch anywhere in that large empty bottom region always resolves to the last row, and even within the packed top band, a tiny finger movement crosses many rows — the entire document's drag range was compressed into a sliver of the screen.
2. **The track and pill read as too small/hard to use** on a tablet — separately from the packing bug, the track itself (20dp wide) and the pill (8dp wide) are visually thin.

Separately, the user wants the *drag preview* redesigned: instead of one tall scrollable dock magnifying every row in the document (today's "expanded panel"), show a small floating carousel of exactly the touched page plus its 2 nearest neighbors on each side, and make the (now full-height, always-visible) track itself the one and only "where am I in the document" indicator — the carousel is a local detail preview, not a second position indicator.

## Scope

`app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt` only, plus its test
file. No changes to `ContinuousReferencePdfPane.kt`, `UnifiedReferenceViewer.kt`,
`AssemblyViewerScreen.kt`, or `ReferencePdfViewerScreen.kt` — `PdfLabelScrollbar`'s external
signature is unchanged, and `PDF_LABEL_SCROLLBAR_IDLE_WIDTH` stays the mechanism callers use to
reserve horizontal space (its value changes, not its meaning or usage).

## Design

### 1. Track spans full height, minus bottom clearance for the nav bar

`PdfLabelScrollbar`'s root `Box` reserves 150dp at the bottom before `fillMaxHeight()`, so the
track never renders into the zone AppScaffold's floating bottom nav bar occupies:

```kotlin
modifier
    .padding(bottom = 150.dp)
    .fillMaxHeight()
    ...
```

Within the remaining height, the idle `LazyColumn`'s `verticalArrangement` changes from
`Arrangement.spacedBy(0.dp)` to `Arrangement.SpaceBetween` — the first page's tick renders at
the very top of the (clearance-adjusted) track, the last page's tick at the very bottom, and
every tick in between is evenly spread across the full span. This directly fixes the drag
precision bug: touch Y now maps proportionally across the whole available height instead of a
packed sliver.

Since idle ticks are tiny (2dp) and existing `displayMode`/bucketing already keeps `entries.size`
low enough to fit the (much larger) drag-chip-sized footprint budget, all entries continue to
fit within the track easily — `SpaceBetween` just changes how the (still fully-visible) set of
ticks distributes across the available space, not how many there are.

### 2. Track and pill get thicker

- `PDF_LABEL_SCROLLBAR_IDLE_WIDTH`: 20dp → 36dp (this constant is what callers already reserve
  as horizontal padding — the value change alone widens the touch target everywhere it's used).
- Pill and tick width: 8dp → 16dp (both — so every row in the column reads as one consistent
  rail, not mismatched widths between the current-page pill and the rest).
- Pill height stays 64dp (length unchanged, per explicit instruction — only thickness grows).

### 3. Track no longer changes appearance between idle and dragging

Today's code branches per-item on `isDragging` inside the `LazyColumn`, switching between a tiny
tick and a full magnified thumbnail chip. That branch is removed — track items are *always* the
tick/pill rendering, in both regimes. The track's `animatedWidth` (today's `animateDpAsState`
between `idleWidth` and `expandedWidth`) is removed entirely; the track is just always
`idleWidth` wide. This is what makes the track a stable, permanent position indicator per the
user's framing: "the main visual representation of the location on the scroll is the actual
scrollbar" — it doesn't transform into something else while dragging.

### 4. Drag preview becomes a floating 5-slot carousel, not a full-document dock

A new small preview renders only while `isDragging`, showing up to 5 entries centered on
`focusIndex`: focus−2, focus−1, **focus (main)**, focus+1, focus+2. Near the start/end of the
document, missing sides simply don't render (no reflow/sliding to compensate — the main slot
stays exactly at the true `focusIndex`).

**Sizing (fixed tiers, not the existing `dockScaleForDistance` gaussian falloff — that curve
assumes many more neighbors exist and fades to near-invisible by the 3rd row out, which doesn't
suit a fixed 5-slot window):**
- Main (distance 0): ~160dp thumbnail height, 2-line caption + range label (same caption/range
  rendering as today's focused-entry treatment).
- ±1 (distance 1): ~100dp, single-line caption, no range label.
- ±2 (distance 2): ~60dp, thumbnail only, no caption (avoids clutter at that size in a compact
  floating card).

**Positioning:** the carousel is a child of the *same* root `Box` the track lives in — Compose
does not clip a child to its parent's own measured bounds by default, and the prior "expanded
panel" (240dp wide, `PDF_LABEL_SCROLLBAR_PANEL_WIDTH`) already relied on exactly this to overlay
the PDF content from inside a Box narrower than itself, so this needs no new mechanism (no
`Popup`, no restructuring the caller hierarchy). The carousel reuses
`PDF_LABEL_SCROLLBAR_PANEL_WIDTH` for its own width. It's positioned via `Modifier.offset(y = ...)`
so its main (center) slot tracks the current touch Y as the user drags, clamped so it can't run
off the top of the screen or into the 150dp bottom clearance.

**Chip styling** (frosted/haze background respecting `LocalLowEndMode`, aspect-ratio-locked
thumbnail sizing capped to the carousel's width, primary-tint overlay on the current page's
thumbnail) is the same visual language as today's chips — reused, not reinvented — just applied
to at most 5 fixed-size slots in a plain `Column` instead of every document entry in a
scrollable `LazyColumn`.

**The carousel does not drive hit-testing.** Drag/tap position → page index still goes entirely
through the track's real `LazyListState.layoutInfo` (`indexForTouchY`, unchanged from the prior
unification work) — the carousel is pure preview rendering off `focusIndex`, exactly as the
user specified: the track is the position source of truth, the carousel is detail-only.

### 5. Thumbnail loading gets lighter

Today's thumbnail loader (`centerOutLoadOrder`) progressively preloads every entry in the
document once dragging starts, because the old dock could show any of them. The carousel only
ever needs at most 5 specific thumbnails (whichever `rowIndex`es are in the current window
around `focusIndex`) at any moment. Replace the center-out preload with a loader that requests
just the entries currently in the 5-slot window, re-triggered as `focusIndex` changes during a
drag. This is a real reduction in decode work, not just a simplification.

### 6. Dead code this leaves behind

Once the drag-chip branch and its gaussian magnification are gone, `dockScaleForDistance` has no
remaining caller — grep to confirm, then remove it and its 4 unit tests
(`dockScaleForDistance_*`), same discipline as the earlier `idleIndexForFraction` cleanup. Once
the thumbnail loader no longer walks the whole document center-out, `centerOutLoadOrder` likely
has no remaining caller either — grep to confirm, then remove it and its 4 unit tests
(`centerOutLoadOrder_*`) too. Confirm both via grep before deleting; don't assume from this doc
alone, since exact call sites are pinned down at implementation time.

## Error Handling / Edge Cases

- Near the very first or last page: carousel renders fewer than 5 slots (main + however many
  real neighbors exist on each side) — no placeholder/empty slots, no reflow.
- Very short documents (fewer than 5 entries total): same rule applies naturally — just render
  whatever exists.
- Carousel vertical clamping: must not extend above the top of the track's own viewport, and
  must not extend into the reserved 150dp bottom clearance — clamp the offset, don't let content
  get cut off mid-chip.
- `rows.isEmpty()` early return at the top of the composable is unchanged.

## Testing

- `PdfLabelScrollbarTest.kt`: remove `dockScaleForDistance_*` and `centerOutLoadOrder_*` tests
  once their functions are confirmed dead (grep first). `indexForTouchY_*` tests are unaffected
  — hit-testing logic doesn't change in this task.
- Manual on-device verification (same procedure as the unification task, now also checking the
  new behavior specifically):
  1. Idle track visually spans from the top of the screen down to ~150dp above the bottom nav
     bar — not a short packed band.
  2. A small, steady finger drag moves through pages proportionally — no longer skipping many
     pages per tiny movement.
  3. While dragging, the floating carousel shows the touched page centered, with up to 2
     shrinking neighbors on each side, and tracks the finger's vertical position.
  4. Releasing lands on the page the carousel's main slot was showing.
  5. Near the first and last pages of the document, the carousel simply shows fewer neighbors —
     no crash, no visual glitch.
  6. The track never changes width or appearance switching into/out of a drag.

## Verification

- `./gradlew.bat :app:compileDebugKotlin -q` compiles clean.
- `./gradlew.bat :app:testDebugUnitTest --tests "*PdfLabelScrollbarTest*"` passes.
- `./gradlew.bat :app:assembleRelease` builds; install via `adb install -r` and manually verify
  per the Testing section above on the connected tablet.
