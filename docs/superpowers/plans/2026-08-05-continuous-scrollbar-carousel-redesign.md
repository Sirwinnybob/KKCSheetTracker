# Continuous-Scroll Scrollbar Carousel Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the idle scrollbar's broken drag precision (ticks packed into a small top band instead of spanning the full track) and replace the full-document magnifying dock preview with a small floating 5-slot carousel, so the track is the one permanent "where am I" indicator in both idle and dragging, and the carousel is a pure local detail preview that follows the finger.

**Architecture:** The track (`LazyColumn`/`LazyListState`, already built in the prior LazyListState-unification work) stops branching its item content on `isDragging` — it always renders the tick/pill, spread across the full clearance-adjusted height via `Arrangement.SpaceBetween` instead of packed via `spacedBy(0.dp)`. A new floating carousel (a plain `Column`, not lazy — at most 5 fixed-size items) renders as a sibling inside the same root `Box` only while dragging, positioned via `Modifier.offset` computed from fixed known tier sizes (not a real-layout measurement or a magnification estimate) so its main slot tracks the touch Y. Hit-testing continues to go through the track's real `LazyListState.layoutInfo` exactly as before — the carousel never participates in it.

**Tech Stack:** Kotlin, Jetpack Compose (`androidx.compose.foundation.lazy`), JUnit (JVM unit tests for pure functions only).

**Reference spec:** `docs/superpowers/specs/2026-08-05-continuous-scrollbar-carousel-redesign-design.md`

---

### Task 1: Rewrite `PdfLabelScrollbar` — full-height track + floating carousel

This is a large, mostly-atomic rewrite of the composable body (imports + full function), like
the prior LazyListState-unification rewrite. It will not compile at intermediate points inside
this task — do the replacement in one pass, then compile once at the end.

`dockScaleForDistance` and `centerOutLoadOrder` (both defined earlier in the file, before this
composable) are **not touched by this task** even though they become unused as a result of it —
that's confirmed and cleaned up in Task 2, after this rewrite lands, so the two tasks each leave
the codebase in a compiling, testable state.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt`

- [ ] **Step 1: Replace the import block**

Replace everything from the `package` declaration through the last `import` line with exactly
this:

```kotlin
package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import com.kkc.sheettracker.ui.viewer.NavigatorRowModel
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
```

This drops `animateDpAsState`/`tween` (no more per-row animation — the carousel uses fixed
tiers, not a continuously-animated gaussian falloff), `CircleShape`/`layout.size` (no more
"dot" fallback — the carousel's outermost ring shows a bare thumbnail, no fallback dot), and
adds `layout.offset` (positions the carousel), `runtime.mutableFloatStateOf` (tracks touch Y),
and `unit.Dp` (the carousel's per-tier sizing helper functions return `Dp`).

- [ ] **Step 2: Update `PDF_LABEL_SCROLLBAR_IDLE_WIDTH`**

Find:
```kotlin
/** Reserved space when idle (collapsed) — just enough for a thin track + thumb. */
internal val PDF_LABEL_SCROLLBAR_IDLE_WIDTH = 20.dp
```
Change `20.dp` to `36.dp`. Leave the doc comment and `PDF_LABEL_SCROLLBAR_PANEL_WIDTH` (still
240.dp, now reused for the carousel's width) untouched.

- [ ] **Step 3: Replace the `PdfLabelScrollbar` composable body**

Find the composable — its doc comment currently starts with `/** Right-edge scrollbar for the
continuous-scroll pane...`, function signature `internal fun PdfLabelScrollbar(...)`. Replace
the ENTIRE function, from that doc comment through its closing `}`, with exactly this:

```kotlin
/**
 * Right-edge scrollbar for the continuous-scroll pane. Always renders as a full-height column of
 * tick marks, one per row, with the current page's tick drawn as a highlighted pill — this is the
 * one and only "where am I in the document" indicator, and it never changes appearance between
 * idle and dragging (it always reads the track's own real layout, never a separate estimate).
 * While dragging, a small floating carousel (up to 5 entries — the touched page plus 2 shrinking
 * neighbors on each side) tracks the finger as a local detail preview; it does NOT drive page
 * selection or represent overall document position — the track does both of those, always.
 * Reserves 150dp of clearance at the bottom so the track never renders into AppScaffold's
 * floating nav bar. Thumbnails keep their real page aspect ratio once rendered (most KKC sheets
 * are landscape); only the up-to-5 entries currently in the carousel window are ever decoded,
 * (re)loaded as that window moves during a drag.
 */
@Composable
internal fun PdfLabelScrollbar(
    modifier: Modifier = Modifier,
    rows: List<NavigatorRowModel>,
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    pdfFileForFilename: (String) -> java.io.File? = { null },
    defaultPdfFilename: String = "",
    hazeState: HazeState? = null
) {
    if (rows.isEmpty()) return
    val density = LocalDensity.current
    val lowEnd = LocalLowEndMode.current
    val listState = rememberLazyListState()
    var isDragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var touchYPx by remember { mutableFloatStateOf(0f) }
    val thumbCache = remember { mutableStateMapOf<Int, Bitmap?>() }

    val idleTickHeight = 2.dp
    val idlePillHeight = 64.dp
    val tickWidth = 16.dp
    val fallbackAspect = 11f / 8.5f // most KKC sheets are landscape
    val idleWidth = PDF_LABEL_SCROLLBAR_IDLE_WIDTH
    val carouselWidth = PDF_LABEL_SCROLLBAR_PANEL_WIDTH
    val rowSpacing = 5.dp
    val panelPadding = 16.dp
    // So the track never renders into AppScaffold's floating bottom nav bar.
    val bottomClearance = 150.dp
    // Rough per-row footprint at rest — used only to decide FULL vs. BUCKETED display mode
    // (i.e. "can every page get its own draggable/previewable row"), not to position anything.
    // Idle ticks are far smaller than this, so bucketed entries always fit the track comfortably.
    val captionAllowance = 20.dp
    val rowFootprintPx = with(density) { (idleTickHeight + captionAllowance + rowSpacing).toPx() }

    // Real viewport height (already clearance-adjusted, since this reads the Lazy list's own
    // layout and the list sits inside the bottom-padded root Box below).
    val trackHeightPx by remember { derivedStateOf { listState.layoutInfo.viewportSize.height.toFloat() } }

    // Adaptive display mode — mirrors github.com/mooalot/alphabetical-scroll-bar's approach of
    // dropping detail as space tightens, driven by measured space vs. page count rather than a
    // hardcoded threshold.
    val displayMode = remember(rows.size, trackHeightPx) {
        if (trackHeightPx <= 0f || rows.size * rowFootprintPx <= trackHeightPx) {
            ScrollbarDisplayMode.FULL
        } else {
            ScrollbarDisplayMode.BUCKETED
        }
    }
    val entries = remember(rows, displayMode, trackHeightPx) {
        if (displayMode == ScrollbarDisplayMode.BUCKETED) {
            val bucketCount = kotlin.math.max(1, (trackHeightPx / rowFootprintPx).toInt())
            val perBucket = kotlin.math.ceil(rows.size / bucketCount.toFloat()).toInt().coerceAtLeast(1)
            rows.indices.chunked(perBucket).map { chunk ->
                val first = rows[chunk.first()]
                val last = rows[chunk.last()]
                val rangeLabel = if (chunk.size == 1) null else "${first.page}–${last.page}"
                ScrollbarEntry(label = first.primaryLabel, rangeLabel = rangeLabel, page = first.page, pageRange = first.page..last.page, rowIndex = chunk.first())
            }
        } else {
            rows.mapIndexed { index, row ->
                ScrollbarEntry(label = row.primaryLabel, rangeLabel = null, page = row.page, pageRange = row.page..row.page, rowIndex = index)
            }
        }
    }

    val currentEntryIndex = remember(entries, currentPage) {
        entries.indexOfFirst { currentPage in it.pageRange }.let { if (it < 0) 0 else it }
    }
    val focusIndex = dragIndex ?: currentEntryIndex

    val engineCache = remember { PdfEngineCache(maxOpen = 2) }
    DisposableEffect(engineCache) {
        onDispose { scrollbarThumbDisposalScope.launch { withContext(NonCancellable) { engineCache.closeAll() } } }
    }

    // Floating preview window — up to 5 entries centered on focusIndex (distance -2..2), ordered
    // top-to-bottom. Recomputed whenever the window moves; only ever needs at most 5 thumbnails,
    // unlike the old full-document dock.
    val carouselSlots = remember(entries, focusIndex) {
        (-2..2).mapNotNull { distance ->
            val slotIndex = focusIndex + distance
            if (slotIndex in entries.indices) distance to entries[slotIndex] else null
        }
    }

    // Idle ticks never show a bitmap, and the carousel only ever needs the entries currently in
    // its window — no reason to decode anything until dragging, and no reason to preload the
    // whole document like the old full-dock design did.
    LaunchedEffect(carouselSlots, defaultPdfFilename, isDragging) {
        if (!isDragging) return@LaunchedEffect
        for ((_, entry) in carouselSlots) {
            if (!isActive) break
            val rowIndex = entry.rowIndex
            if (thumbCache.containsKey(rowIndex)) continue
            val row = rows[rowIndex]
            val filename = row.source?.pdfFilename?.takeIf { it.isNotBlank() } ?: defaultPdfFilename
            val sourcePage = row.source?.page?.takeIf { it > 0 } ?: row.page
            val file = filename.takeIf { it.isNotBlank() }?.let(pdfFileForFilename)
            val bitmap = if (file != null) {
                withContext(Dispatchers.IO) { engineCache.get(file).renderThumbnail(sourcePage - 1, maxWidth = 200) }
            } else {
                null
            }
            thumbCache[rowIndex] = bitmap
            yield()
        }
    }

    // Keeps the current page's row anchored/visible whenever the user isn't actively dragging
    // this scrollbar themselves. BUCKETED mode already sizes `entries` so idle-tick rows — far
    // smaller than the drag-chip-sized footprint bucketing is computed against — always fit
    // inside trackHeightPx, so in practice this is a no-op safety net, not the mechanism that
    // positions the pill: the pill's real screen position always comes straight from
    // listState.layoutInfo in the item content below.
    LaunchedEffect(currentEntryIndex, isDragging) {
        if (!isDragging) listState.scrollToItem(currentEntryIndex)
    }

    fun hitTest(touchY: Float): Int = indexForTouchY(
        items = listState.layoutInfo.visibleItemsInfo.map { ItemBounds(it.index, it.offset, it.size) },
        touchY = touchY,
        fallback = focusIndex
    )

    Box(
        modifier = modifier
            .padding(bottom = bottomClearance)
            .fillMaxHeight()
            .width(idleWidth)
            .pointerInput(entries.size, displayMode) {
                detectTapGestures { offset -> onPageSelected(entries[hitTest(offset.y)].page) }
            }
            .pointerInput(entries.size, displayMode) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        touchYPx = offset.y
                        val index = hitTest(offset.y)
                        dragIndex = index
                        onPageSelected(entries[index].page)
                    },
                    onVerticalDrag = { change, _ ->
                        touchYPx = change.position.y
                        val index = hitTest(change.position.y)
                        dragIndex = index
                        onPageSelected(entries[index].page)
                    },
                    onDragEnd = { isDragging = false; dragIndex = null },
                    onDragCancel = { isDragging = false; dragIndex = null }
                )
            }
    ) {
        // The track — always the tick/pill rendering, in both idle and dragging. This is the
        // permanent position indicator; the carousel below is a separate, purely local preview.
        LazyColumn(
            state = listState,
            modifier = Modifier.align(Alignment.TopEnd).fillMaxSize(),
            userScrollEnabled = false,
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.SpaceBetween,
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.rowIndex }) { index, _ ->
                val isCurrent = index == currentEntryIndex
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .width(tickWidth)
                        .height(if (isCurrent) idlePillHeight else idleTickHeight)
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        )
                )
            }
        }

        if (isDragging) {
            // Fixed size tiers, not a continuous falloff — the carousel only ever shows exactly
            // these 5 roles (main/±1/±2), so there's no "many more neighbors fading to zero" case
            // to model the way the old whole-document dock needed to.
            fun thumbHeightForDistance(distance: Int): Dp = when (kotlin.math.abs(distance)) {
                0 -> 160.dp
                1 -> 100.dp
                else -> 60.dp
            }
            // Whole-chip footprint (thumbnail + its own chrome/padding + caption allowance where
            // applicable) for each role, used only to position the carousel so its main slot
            // tracks the touch point — NOT a measurement of anything dynamic (unlike the old
            // per-row cumulative-height estimate this whole redesign replaced), just the known
            // sum of fixed constants below, so there's no real-vs-estimate drift possible here.
            fun footprintForDistance(distance: Int): Dp = when (kotlin.math.abs(distance)) {
                0 -> 222.dp // 160 thumbnail + 18 chip chrome + ~44 two-line caption + range label
                1 -> 138.dp // 100 thumbnail + 18 chip chrome + ~20 one-line caption
                else -> 78.dp // 60 thumbnail + 18 chip chrome, no caption
            }

            val footprintsPx = carouselSlots.associate { (distance, _) -> distance to with(density) { footprintForDistance(distance).toPx() } }
            var mainTopPx = 0f
            for ((distance, _) in carouselSlots) {
                if (distance >= 0) break
                mainTopPx += (footprintsPx[distance] ?: 0f) + with(density) { rowSpacing.toPx() }
            }
            val mainCenterPx = mainTopPx + (footprintsPx[0] ?: 0f) / 2f
            val totalContentPx = footprintsPx.values.sum() + (carouselSlots.size - 1).coerceAtLeast(0) * with(density) { rowSpacing.toPx() }
            val maxTopPx = (trackHeightPx - totalContentPx).coerceAtLeast(0f)
            val carouselTopPx = (touchYPx - mainCenterPx).coerceIn(0f, maxTopPx)
            val carouselTopDp = with(density) { carouselTopPx.toDp() }

            val frostedTokens = LocalKKCThemeTokens.current.frosted
            val frostedAlpha = frostedTokens.backgroundAlpha.coerceIn(0.5f, 0.95f)
            val panelSurfaceColor = MaterialTheme.colorScheme.surface
            val safeHazeState = hazeState
            val hazeAvailable = safeHazeState != null && !lowEnd.blurDisabled
            val chipColor = if (hazeAvailable) {
                androidx.compose.ui.graphics.Color.Transparent
            } else {
                panelSurfaceColor.copy(alpha = frostedAlpha)
            }
            val chipHazeModifier = if (safeHazeState != null && hazeAvailable) {
                Modifier.hazeEffect(
                    safeHazeState,
                    style = HazeDefaults.style(
                        backgroundColor = panelSurfaceColor.copy(alpha = frostedAlpha),
                        blurRadius = frostedTokens.blurDp.coerceAtLeast(1f).dp
                    )
                )
            } else {
                Modifier
            }
            val chipShadowElevation = if (lowEnd.shadowsDisabled) 0.dp else 2.dp
            val maxThumbWidthPx = with(density) { (carouselWidth - panelPadding * 2).toPx() }
            val maxThumbWidth = carouselWidth - panelPadding * 2

            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = carouselTopDp)
                    .width(carouselWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(rowSpacing)
            ) {
                carouselSlots.forEach { (distance, entry) ->
                    val thumbHeightDp = thumbHeightForDistance(distance)
                    val hPx = with(density) { thumbHeightDp.toPx() }
                    val bitmap = thumbCache[entry.rowIndex]
                    val aspect = bitmap?.let { it.width.toFloat() / it.height.toFloat().coerceAtLeast(1f) } ?: fallbackAspect
                    val uncappedWidthPx = hPx * aspect
                    val (effectiveHeightPx, effectiveWidthPx) = if (uncappedWidthPx > maxThumbWidthPx) {
                        (maxThumbWidthPx / aspect) to maxThumbWidthPx
                    } else {
                        hPx to uncappedWidthPx
                    }
                    val rowHeight = with(density) { effectiveHeightPx.toDp() }
                    val thumbWidth = with(density) { effectiveWidthPx.toDp() }
                    val isMain = distance == 0
                    val showLabel = kotlin.math.abs(distance) <= 1

                    androidx.compose.material3.Surface(
                        modifier = Modifier.padding(vertical = 3.dp, horizontal = panelPadding),
                        shape = RoundedCornerShape(10.dp),
                        color = chipColor,
                        shadowElevation = chipShadowElevation,
                        tonalElevation = 0.dp
                    ) {
                        Box(modifier = chipHazeModifier) {
                            Column(
                                modifier = Modifier.padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(thumbWidth)
                                        .height(rowHeight)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .then(
                                            if (isMain) {
                                                Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                            } else {
                                                Modifier
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = entry.label,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Fit
                                        )
                                    } else {
                                        Text(
                                            text = entry.page.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (showLabel) {
                                    Text(
                                        text = entry.label,
                                        modifier = Modifier.widthIn(max = maxThumbWidth + 20.dp).padding(top = 2.dp),
                                        style = if (isMain) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        maxLines = if (isMain) 2 else 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isMain && entry.rangeLabel != null) {
                                        Text(
                                            text = entry.rangeLabel,
                                            modifier = Modifier.widthIn(max = maxThumbWidth + 20.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew.bat :app:compileDebugKotlin -q`
Expected: no output (clean compile). If there are unresolved-reference or unused-import errors,
check for a leftover use of a removed symbol (`animateDpAsState`, `tween`, `radius`,
`baseHeight`, `maxHeight`, `animatedWidth`, `CircleShape`) that Step 3 should have fully
replaced, or a missing import Step 1 should have added.

- [ ] **Step 5: Run the full scrollbar test suite**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PdfLabelScrollbarTest*"`
Expected: BUILD SUCCESSFUL — all existing tests (`dockScaleForDistance_*`,
`centerOutLoadOrder_*`, `indexForTouchY_*`) still pass. (They still exist as functions right now
even though `dockScaleForDistance`/`centerOutLoadOrder` are no longer called from the
composable — Task 2 removes them.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt
git commit -m "$(cat <<'EOF'
refactor: full-height scrollbar track + floating carousel preview

The idle track packed all its ticks into a small band at the top of
the screen (Arrangement.spacedBy(0.dp) on very short rows), so a tiny
drag movement jumped many pages — confirmed on-device via screenshot.
Track now spans the full height (minus clearance for the floating nav
bar) via Arrangement.SpaceBetween, is wider/thicker, and never changes
appearance between idle and dragging — it's the one permanent position
indicator. The old full-document magnifying dock preview is replaced
by a small floating carousel (main page + 2 shrinking neighbors each
side) that follows the touch point but never drives page selection —
the track's real layout still does that, unchanged.
EOF
)"
```

---

### Task 2: Remove now-dead `dockScaleForDistance` and `centerOutLoadOrder`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbarTest.kt`

- [ ] **Step 1: Confirm both are actually unused**

Run: `grep -rn "dockScaleForDistance\|centerOutLoadOrder" app/src/main/java`
Expected: only their own definitions in `PdfLabelScrollbar.kt` — no call sites anywhere else
(including within `PdfLabelScrollbar.kt` itself, now that Task 1 landed). If either has a
remaining call site, STOP — the premise is wrong for that one, leave it in place and only
remove the one that's genuinely dead.

- [ ] **Step 2: Delete the dead functions**

In `PdfLabelScrollbar.kt`, delete (assuming Step 1 confirmed both are dead):

```kotlin
/** Falloff for the dock-style magnification — smooth gaussian taper across neighbors. */
internal fun dockScaleForDistance(distance: Int, radius: Float): Float {
    if (radius <= 0f) return if (distance == 0) 1f else 0f
    val t = distance.toDouble() / radius.toDouble()
    return kotlin.math.exp(-(t * t)).toFloat()
}
```

and

```kotlin
/** Center-out load order so the focused page's thumbnail (and its neighbors) load first. */
internal fun centerOutLoadOrder(count: Int, focus: Int): List<Int> {
    if (count <= 0) return emptyList()
    val center = focus.coerceIn(0, count - 1)
    val out = mutableListOf(center)
    var radius = 1
    while (out.size < count) {
        val left = center - radius
        val right = center + radius
        if (left >= 0) out += left
        if (right < count) out += right
        radius++
    }
    return out
}
```

- [ ] **Step 3: Delete their tests**

In `PdfLabelScrollbarTest.kt`, delete every test whose name starts with `dockScaleForDistance_`
(4 tests: `isFullScaleAtZeroDistance`, `decreasesAsDistanceGrows`, `approachesZeroFarFromFocus`,
`zeroRadiusOnlyMagnifiesExactMatch`) and every test whose name starts with `centerOutLoadOrder_`
(4 tests: `startsAtFocusAndAlternatesOutward`, `clampsFocusToValidRange`,
`handlesFocusAtEdge`, `returnsEmptyForEmptyDocument`) — 8 tests total. Leave `indexForTouchY_*`
tests untouched.

- [ ] **Step 4: Run the test suite**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PdfLabelScrollbarTest*"`
Expected: BUILD SUCCESSFUL, remaining `indexForTouchY_*` tests (4) pass.

- [ ] **Step 5: Compile**

Run: `./gradlew.bat :app:compileDebugKotlin -q`
Expected: no output (clean compile).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt app/src/test/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbarTest.kt
git commit -m "chore: remove dockScaleForDistance and centerOutLoadOrder, dead after carousel redesign"
```

---

### Task 3: Build, install, and verify on-device

**Files:** none (build/verification only).

- [ ] **Step 1: Build the release APK**

Run: `./gradlew.bat :app:assembleRelease -q`
Expected: no output (clean build). Output: `app/build/outputs/apk/release/app-release.apk`.

- [ ] **Step 2: Confirm a tablet is connected**

Run: `adb devices`
Expected: at least one line ending in `device`.

- [ ] **Step 3: Install, preserving app data**

Run: `adb install -r "app/build/outputs/apk/release/app-release.apk"`
Expected: `Success`.

- [ ] **Step 4: Manually verify on the tablet**

Open a job with a long PDF reference document in continuous-scroll mode. Perform, in order:

1. Confirm the idle track visually spans from near the top of the screen down to roughly 150dp
   above the bottom nav bar — not a short packed band at the top.
2. Confirm the pill (current page) and ticks read as noticeably thicker than before.
3. Perform a small, steady drag (a few centimeters of finger movement) and confirm it moves
   through pages proportionally — it should NOT skip many pages for a tiny movement anymore.
4. While dragging, confirm a small floating carousel appears showing the touched page centered,
   with up to 2 shrinking neighbor thumbnails on each side, and that it visually tracks the
   finger's vertical position as you drag.
5. Release and confirm the page that opens matches what the carousel's center (main) slot was
   showing.
6. Drag to near the very first page and near the very last page of the document — confirm the
   carousel simply shows fewer neighbors near those edges (not a crash, not empty placeholder
   slots).
7. Confirm the track itself never changes width or appearance switching into/out of a drag —
   only the carousel appears/disappears.
8. Tap directly on the track (no drag) at a few different heights — confirm each tap navigates
   to a page in the expected vicinity, proportional to the tap's position on the track.

If any check fails, capture exactly what diverged (which step, what was expected vs. observed)
— that's Phase 1 evidence for a follow-up systematic-debugging pass, not something to patch
blind.

- [ ] **Step 5: Final commit if on-device testing required tweaks**

Only if Step 4 surfaced a fix: stage the specific files changed, commit with a message
describing what on-device behavior was wrong and what changed. Skip this step entirely if Step
4 passed clean.
