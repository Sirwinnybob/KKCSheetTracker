# Continuous-Scroll Scrollbar: Unify Idle Pill and Drag Preview on One LazyListState

## Goal

The continuous-scroll PDF scrollbar (`PdfLabelScrollbar`) has two independently-computed
representations of "where is the current page": an idle resting pill (a small floating thumb)
and an expanded drag preview (a magnified dock panel). They currently disagree — the pill's
resting position, and the page a drag actually lands you on, don't reliably match where the
drag preview showed. Two prior fixes (breaking a scroll-position feedback loop in
`ContinuousReferencePdfPane`, then caching a real measured position per row) each closed one
gap but the mismatch keeps resurfacing in new forms, because the two representations are
fundamentally different math models being forced to agree after the fact.

This redesign eliminates the class of bug at its root: replace the hand-rolled cumulative
position estimate with a single `LazyListState`, shared by both the idle pill and the drag
preview. There is no second model to drift out of sync with the first.

## Scope

`app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt` only. External
composable signature (`rows`, `currentPage`, `onPageSelected`, `pdfFileForFilename`,
`defaultPdfFilename`, `hazeState`) is unchanged, so `UnifiedReferenceViewer.kt`,
`AssemblyViewerScreen.kt`, and `ReferencePdfViewerScreen.kt` need no changes.

`PdfLabelScrollbarTest.kt` needs updating: tests for functions this redesign removes are
deleted; tests for functions that survive unchanged (`dockScaleForDistance`,
`centerOutLoadOrder`) are kept as-is.

Not in scope: any change to `ContinuousReferencePdfPane.kt` (the loop fix already landed there
and stays as-is), any change to the main reference-pane's own scrolling behavior, any change to
single-page (non-continuous) mode.

## Current Design (for context)

- Idle: a thin track (`PDF_LABEL_SCROLLBAR_IDLE_WIDTH`) with a small pill positioned via
  `idleFraction = centerPx / restingContentHeightPx`, where `centerPx` and
  `restingContentHeightPx` come from `cumulativeTopPx`/`rowFootprintPxAt` — a hand-tuned
  estimate of how tall each row would be if magnified around the current page (dock-style
  falloff via `dockScaleForDistance`), summed across every row in the document on every
  recomposition.
- Dragging: a `Column` inside `verticalScroll(scrollState)` renders every entry as a real
  Compose row (thumbnail + caption), magnified by distance from the touched row
  (`focusIndex`). Real per-row centers are captured via `onGloballyPositioned` into a
  `rowCenterY` map, used only for that drag's own hit-testing, then discarded
  (`rowCenterY.clear()`) when the drag ends.
- The estimate (idle) and the real layout (dragging) are two different computations of "where
  is page N," reconciled only by trying to make the estimate closely approximate the real
  layout's math — which requires guessing real caption text height, chip padding, and
  inter-row spacing accurately. Confirmed on-device: the estimate overshoots (e.g. real
  measured center 927px vs. estimated pill position ~1370px on a 2664px track), because the
  estimate assumes every row costs the same caption-chrome space, when most rows (far from the
  focused page) render only a bare 3dp dot.

## New Design

### Single source of truth: `LazyListState`

Replace `rememberScrollState()` + `Column(Modifier.verticalScroll(...))` with a `LazyColumn` +
`rememberLazyListState()`. The list is **always mounted** (not conditionally composed only
while `isDragging`), so both the idle pill and the drag preview read the same
`LazyListState.layoutInfo` at all times. `LazyColumn` only measures items near its visible
window regardless of total item count, so keeping it mounted continuously is cheap even for
long documents — actually cheaper than today's idle-mode estimate, which sums every row in the
document on every recomposition.

`focusIndex` (`dragIndex ?: currentEntryIndex`, unchanged from today) continues to drive each
item's target size via the existing `dockScaleForDistance` falloff — this part of the visual
language doesn't change.

### Idle regime

Track width stays `idleWidth`. Each item's target height collapses toward `baseHeight`
(currently 2dp) regardless of distance from focus — no bulge magnification at rest, since
magnification is only meaningful while actively dragging near a touch point. The current
page's tick is drawn slightly larger/highlighted so it reads as "the pill" — it is a real item
in the list, not a separately-rendered floating widget. No thumbnail bitmaps are composed for
idle ticks.

The list's scroll position is kept synced to `currentPage` whenever the user isn't actively
dragging it: a `LaunchedEffect(currentEntryIndex, isDragging)` calls
`listState.scrollToItem(currentEntryIndex)` (an instant snap, not `animateScrollToItem` —
there's nothing to visually animate for a collapsed tick list) whenever `currentEntryIndex`
changes and `isDragging` is false. This mirrors the `isProgrammaticScroll` pattern already
used in `ContinuousReferencePdfPane.kt` to avoid feedback loops, applied here defensively even
though the resting list doesn't have an equivalent self-reporting callback today.

The pill's on-screen position is read directly:
`listState.layoutInfo.visibleItemsInfo.find { it.index == currentEntryIndex }` — its `offset`
and `size` give the real, already-being-displayed center. If the current item isn't in the
visible window (shouldn't normally happen, since the sync effect above keeps it anchored, but
guards against a transient frame where it hasn't caught up yet), fall back to rendering nothing
for that frame rather than an estimate — a one-frame gap is invisible; a wrong position is not.

### Drag regime

Track width animates to `expandedWidth`, same `animateDpAsState` as today. Items magnify per
the existing bulge math, thumbnails render, chip visuals (frosted background, caption,
range label) are unchanged from the current implementation.

`onDragStart`: convert the touch Y into an index by hit-testing the **current** (already-real,
since the list stays mounted) `listState.layoutInfo.visibleItemsInfo` — no estimate needed,
because the list was already showing real idle-tick positions the instant before the drag
began. Then `listState.scrollBy`/`animateScrollToItem` so the touched row settles near the
finger, using the list's own real current layout to compute the delta (replacing today's
`cumulativeTopPx`-based `estimatedCenterY` guess).

`onVerticalDrag`: same hit-test function (real `visibleItemsInfo`, not a separate
`indexForExpandedWindowY`), called on every drag frame, same as today's approach — this part
of the interaction model doesn't change, only its data source does.

`onDragEnd`/`onDragCancel`: no special "snapshot the real position" step is needed anymore
(today's `realRestCenterByRowIndex` cache is removed) — the idle pill will simply read the
list's real current layout on the very next frame, which is already sitting at the position
the drag preview was just showing, because it's the same list.

### Removed

`cumulativeTopPx`, `rowFootprintPxAt`, `estimatedTotalHeightPx`, `restingContentHeightPx`,
`effectiveTrackHeightPx`, `realRestCenterByRowIndex`, `rowCenterY`, `indexForIdleFraction`,
`indexForExpandedWindowY`, `dockWindowY` and the `onGloballyPositioned`-based window-coordinate
conversion it enabled. Touch coordinates from `detectVerticalDragGestures`/
`detectTapGestures` are already in the scrollbar `Box`'s own local coordinate space, which
matches `LazyListState.layoutInfo` item offsets directly — no window-position bookkeeping is
needed with the new approach.

`trackHeightPx` (previously hand-tracked via `onGloballyPositioned`) is replaced by
`listState.layoutInfo.viewportSize.height`, which `LazyListState` already provides.

### Thumbnail loading

Since idle ticks never show a thumbnail bitmap, defer the existing center-out thumbnail
preload (`LaunchedEffect(entries, defaultPdfFilename)`) so it only starts once `isDragging`
first becomes `true`, rather than unconditionally on mount. This reduces idle-mode memory/CPU
work — pure upside from the same restructuring, not a separate ask.

### Display mode (FULL vs BUCKETED)

Unchanged trigger logic (adaptive: full detail if it fits, page-range bucketing once it
doesn't). The space-available input (`trackHeightPx` in today's code) becomes
`listState.layoutInfo.viewportSize.height` — same real value, just sourced from the Lazy
list's own layout instead of a manually-tracked `onGloballyPositioned` callback.

## Error Handling / Edge Cases

- `rows.isEmpty()` guard at the top of the composable is unchanged.
- First-frame-not-yet-laid-out (`visibleItemsInfo` empty): tap/drag hit-testing returns a safe
  fallback (current `focusIndex`) instead of crashing on an empty lookup — same defensive
  posture as today's `trackHeightPx <= 0f` handling, just against the Lazy layout's own
  "not measured yet" state.
- Very long documents: `LazyColumn` composes only near-viewport items regardless of total page
  count, so this redesign scales better than today's approach (which sums every row's estimated
  height on every recomposition, in both idle and dragging modes).

## Testing

- `PdfLabelScrollbarTest.kt`: remove tests for the deleted position-estimate functions
  (`indexForIdleFraction`, if it's tested; any test asserting `cumulativeTopPx`/
  `rowFootprintPxAt` behavior). Keep tests for `dockScaleForDistance` and
  `centerOutLoadOrder` unchanged — neither is touched by this redesign.
- Add a test (if feasible without a full Compose test harness) for the new hit-test-by-real-
  layout function, or note in the implementation plan that it's verified on-device only if a
  unit test isn't practical against `LazyListState.layoutInfo` outside a Compose test rule.
- Manual verification (as done for the two prior fixes): build release, install via
  `adb install -r`, drag the scrollbar to multiple depths in a long document, confirm the pill
  rests exactly where the last drag preview highlighted, confirm the page that actually opens
  matches, confirm no jump/stutter on release.

## Verification

- `./gradlew.bat :app:compileDebugKotlin` compiles clean.
- `./gradlew.bat :app:testDebugUnitTest --tests "*PdfLabelScrollbarTest*"` passes.
- `./gradlew.bat :app:assembleRelease` builds; install via `adb install -r` and manually verify
  per the Testing section above on the connected tablet.
- Preserve unrelated working-tree files (in particular, the whole-stack pinch-zoom changes
  already present in `ContinuousReferencePdfPane.kt` are out of scope and must not be touched
  or reverted).
