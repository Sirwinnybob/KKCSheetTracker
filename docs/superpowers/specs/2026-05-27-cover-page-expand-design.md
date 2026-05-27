# Cover Page Expand — Design Spec

## What & Why

Job board cards are small in a 3-column grid. The cover sheet already contains all the information operators need (job number, name, colors) but is hard to read at thumbnail size. Long-pressing a card expands the cover page to full screen so operators can read it without navigating into the job. An X button and the back gesture shrink it back to its original card position.

---

## Architecture

All state is internal to `JobBoardGrid`. No callers change.

### New file: `CoverPageOverlay.kt`
- Location: `app/.../ui/components/CoverPageOverlay.kt`
- A full-screen composable that renders:
  - Black `Box` background (fades in/out via `AnimatedVisibility`)
  - The cover page `Image` with a `sharedElement` modifier (drives the shared element transition)
  - Pinch-to-zoom + pan via `rememberTransformableState`
  - X (`Close`) icon button fixed to the top-right corner
- Accepts: `item: JobBoardItem`, `thumbnail: Bitmap?`, `jobRepository: JobRepository`, `sharedTransitionScope: SharedTransitionScope`, `animatedVisibilityScope: AnimatedVisibilityScope`, `onDismiss: () -> Unit`
- On first composition, kicks off a coroutine on `Dispatchers.IO` to re-render the PDF at full screen width. When ready, crossfades from the 600 px thumbnail to the high-res bitmap.
- High-res bitmap is held in local `remember` state and is not written back to the card cache.

### Modified file: `JobBoardGrid.kt`

**`JobBoardGrid` composable:**
- Adds `var expandedItem by remember { mutableStateOf<JobBoardItem?>(null) }`
- Wraps everything in `SharedTransitionLayout`
- Inside a `Box(Modifier.fillMaxSize())`:
  1. The existing `LazyVerticalGrid` (unchanged except cards get two extra params — see below)
  2. An `AnimatedVisibility(visible = expandedItem != null, enter = fadeIn(), exit = fadeOut())` containing `CoverPageOverlay`. The `thumbnail` passed to the overlay is retrieved as `cardStateCache.get(expandedItem!!.folderName)?.thumbnail` — the cache is module-level in `JobBoardGrid.kt` so no extra plumbing is needed.
- `BackHandler(enabled = expandedItem != null) { expandedItem = null }` intercepts the system back gesture/button while expanded

**`JobBoardCard` composable:**
- New parameters: `sharedTransitionScope: SharedTransitionScope`, `expandedFolderName: String?`, `onLongClick: () -> Unit`
- `Card(onClick = ...)` replaced with a `Card` whose `Modifier` includes `combinedClickable(onClick = onClick, onLongClick = onLongClick)`
- The thumbnail `Image` (and the placeholder `Box`) both gain `Modifier.sharedElementWithCallerManagedVisibility(key = "cover:${item.folderName}", visible = expandedFolderName != item.folderName)` so the card's copy hides while the overlay's copy is animating

---

## UI

### Job board card (long-press state)
- The thumbnail (or placeholder box) fades out via `sharedElementWithCallerManagedVisibility` as soon as long-press fires.
- The banner and footer remain visible in the card while the overlay is open (they're not part of the shared element).

### Full-screen overlay
- Background: `Color.Black` at alpha 0.92 — dark enough to read the page, slight transparency signals overlay.
- Image: fills the screen width at the natural page aspect ratio, centered vertically. Starts with the 600 px thumbnail bitmap. When the high-res render completes, `Crossfade` swaps to the sharper bitmap.
- High-res render: `PdfRenderEngine.renderThumbnail(pageIndex = 0, maxWidth = screenWidthPx)` where `screenWidthPx` is obtained from `LocalConfiguration.current.screenWidthDp * density`.
- If the job has no delivery sheet (thumbnail is null), the overlay shows a full-screen version of the same colored placeholder used in the card (same `placeholderColor` logic).
- X button: `IconButton` with `Icons.Default.Close`, white tint, positioned `Modifier.align(Alignment.TopEnd).padding(16.dp)` inside the overlay `Box`. Always on top (drawn after the image in the `Box`).

### Pinch-to-zoom
- `rememberTransformableState { zoomChange, panChange, _ -> ... }` tracks `scale: Float` and `offset: Offset`.
- Scale clamped to `1f..5f`.
- Offset clamped so the image edge cannot be dragged past the screen edge:  
  `maxOffset = (imageSize * (scale - 1f)) / 2f`  
  `offset = offset.coerceIn(-maxOffset, maxOffset)` applied per axis.
- Double-tap (`detectTapGestures(onDoubleTap = { ... })`) animates scale back to `1f` and offset back to `Offset.Zero` using `Animatable` with `spring()`.
- State is local to `CoverPageOverlay` — resets automatically when the overlay is dismissed and recomposed.

---

## Backwards Compatibility

- No data model changes.
- No network changes.
- The 4 screens that call `JobBoardGrid` (`JobBrowserScreen`, `HardwoodsJobsScreen`, `SpecialtyJobsScreen`, `AssemblyJobsScreen`) are unchanged.
- `clearBoardCardCache()` function and `LruCache` are unchanged.

---

## Verification

1. **Expand:** Long press any card with a thumbnail → overlay appears with an animation that originates from the card's position, background fades to near-black, card thumbnail hides during transition. Within ~1 second the image sharpens to full-res.

2. **Dismiss via X:** Tap X → image animates back to the card's grid position, background fades out, card thumbnail reappears.

3. **Dismiss via back:** While overlay is open, press back → same close animation as X.

4. **Regular tap unchanged:** Short tap on a card still navigates into the job normally.

5. **Pinch-to-zoom:** While overlay is open, pinch to zoom in. Image scales up, can be panned. Image edge cannot be dragged off screen. Double-tap resets to 1×. Closing while zoomed in works without glitch.

6. **No delivery sheet:** Long press a card with no cover page (colored placeholder). The placeholder color fills the screen. X closes normally.

7. **All 4 screens:** Expand/dismiss works identically from `JobBrowserScreen`, `HardwoodsJobsScreen`, `SpecialtyJobsScreen`, `AssemblyJobsScreen` with no code changes to those screens.
