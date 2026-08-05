# Continuous-Scroll Scrollbar LazyListState Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `PdfLabelScrollbar`'s two independently-computed position models (an estimate-based idle pill, a real-layout drag preview) with a single shared `LazyListState`, so the idle pill and the drag preview can never disagree.

**Architecture:** Swap the `rememberScrollState()` + `Column(Modifier.verticalScroll(...))` currently used only while dragging for a `LazyColumn` + `LazyListState` that stays mounted at all times. One `focusIndex` still drives per-item magnification via the existing `dockScaleForDistance` falloff; idle rows collapse to plain ticks (the current page's tick is the pill) and dragging rows expand into the existing thumbnail+caption chips. Both regimes read the same `LazyListState.layoutInfo` for hit-testing and positioning — there is no second, hand-estimated model left to drift out of sync.

**Tech Stack:** Kotlin, Jetpack Compose (`androidx.compose.foundation.lazy`), JUnit (JVM unit tests, no Compose test harness in this project).

**Reference spec:** `docs/superpowers/specs/2026-08-05-continuous-scrollbar-lazy-unification-design.md`

---

### Task 1: Remove dead `idleIndexForFraction` function and its tests

`idleIndexForFraction` (top-level, `PdfLabelScrollbar.kt:87-94`) is pre-existing dead code — the composable actually uses a different local closure (`indexForIdleFraction`) that Task 3 removes anyway. It's tested but never called. Clear it out first so Task 3's diff isn't cluttered with an unrelated deletion.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbarTest.kt`

- [ ] **Step 1: Confirm it's actually unused**

Run: `grep -rn "idleIndexForFraction" app/src/main/java`
Expected: only the one definition at `PdfLabelScrollbar.kt:89` — no call sites.

- [ ] **Step 2: Delete the function**

In `PdfLabelScrollbar.kt`, delete lines 87-94:

```kotlin
/** Maps a touch fraction along the idle (collapsed) track to a row index — uniform spacing,
 * used only before the expanded dock has laid out real (unevenly magnified) row positions. */
internal fun idleIndexForFraction(rowCount: Int, fraction: Float): Int {
    if (rowCount <= 0) return 0
    if (rowCount == 1) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    return (clamped * (rowCount - 1)).roundToInt().coerceIn(0, rowCount - 1)
}
```

Also delete the now-unused import on line 62:

```kotlin
import kotlin.math.roundToInt
```

- [ ] **Step 3: Delete its tests**

In `PdfLabelScrollbarTest.kt`, delete lines 58-78 (the five `idleIndexForFraction_*` tests):

```kotlin
    @Test
    fun idleIndexForFraction_mapsZeroToFirstIndex() {
        assertEquals(0, idleIndexForFraction(rowCount = 10, fraction = 0f))
    }

    @Test
    fun idleIndexForFraction_mapsOneToLastIndex() {
        assertEquals(9, idleIndexForFraction(rowCount = 10, fraction = 1f))
    }

    @Test
    fun idleIndexForFraction_clampsOutOfRangeFractions() {
        assertEquals(0, idleIndexForFraction(rowCount = 10, fraction = -0.5f))
        assertEquals(9, idleIndexForFraction(rowCount = 10, fraction = 1.5f))
    }

    @Test
    fun idleIndexForFraction_returnsZeroForEmptyOrSingleRow() {
        assertEquals(0, idleIndexForFraction(rowCount = 0, fraction = 0.5f))
        assertEquals(0, idleIndexForFraction(rowCount = 1, fraction = 0.5f))
    }
```

- [ ] **Step 4: Run the test suite**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PdfLabelScrollbarTest*"`
Expected: BUILD SUCCESSFUL, remaining tests (dockScaleForDistance/centerOutLoadOrder) pass.

- [ ] **Step 5: Compile**

Run: `./gradlew.bat :app:compileDebugKotlin -q`
Expected: no output (clean compile) — confirms nothing else referenced the removed function/import.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt app/src/test/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbarTest.kt
git commit -m "chore: remove dead idleIndexForFraction from PdfLabelScrollbar"
```

---

### Task 2: Add `ItemBounds` and `indexForTouchY` (TDD)

The new hit-test needs to work against real `LazyListState.layoutInfo.visibleItemsInfo` data, but that type requires a Compose runtime to construct. Decouple the hit-test itself into a pure function over a plain data class, so it's unit-testable on the JVM exactly like `dockScaleForDistance`/`centerOutLoadOrder` already are.

**Files:**
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbarTest.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt`

- [ ] **Step 1: Write the failing tests**

Add to `PdfLabelScrollbarTest.kt` (after the `centerOutLoadOrder` tests, before the closing brace):

```kotlin
    @Test
    fun indexForTouchY_returnsFallbackWhenNoItems() {
        assertEquals(7, indexForTouchY(items = emptyList(), touchY = 100f, fallback = 7))
    }

    @Test
    fun indexForTouchY_picksItemContainingTouch() {
        val items = listOf(
            ItemBounds(index = 0, offset = 0, size = 50),
            ItemBounds(index = 1, offset = 50, size = 50),
            ItemBounds(index = 2, offset = 100, size = 50)
        )
        assertEquals(1, indexForTouchY(items, touchY = 60f, fallback = 0))
    }

    @Test
    fun indexForTouchY_picksNearestCenterWhenBetweenItems() {
        val items = listOf(
            ItemBounds(index = 0, offset = 0, size = 10),
            ItemBounds(index = 1, offset = 200, size = 10)
        )
        assertEquals(0, indexForTouchY(items, touchY = 20f, fallback = -1))
        assertEquals(1, indexForTouchY(items, touchY = 190f, fallback = -1))
    }

    @Test
    fun indexForTouchY_handlesSingleItem() {
        val items = listOf(ItemBounds(index = 3, offset = 40, size = 20))
        assertEquals(3, indexForTouchY(items, touchY = 0f, fallback = -1))
        assertEquals(3, indexForTouchY(items, touchY = 1000f, fallback = -1))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PdfLabelScrollbarTest*"`
Expected: FAIL — `Unresolved reference: ItemBounds` / `Unresolved reference: indexForTouchY` (compile error in the test source set).

- [ ] **Step 3: Add the implementation**

In `PdfLabelScrollbar.kt`, add after `centerOutLoadOrder` (after line 85, before the `idleIndexForFraction`-shaped gap Task 1 already closed):

```kotlin
/** Real (not estimated) bounds of one rendered scrollbar row, in the scrollbar's own local
 * coordinate space — sourced from LazyListState.layoutInfo.visibleItemsInfo. Decoupled into a
 * plain data class (rather than using LazyListItemInfo directly) so the hit-test below is a pure
 * function, testable without a Compose runtime. */
internal data class ItemBounds(val index: Int, val offset: Int, val size: Int)

/** Nearest visible row to a touch Y, by comparing the touch position against each row's real
 * measured center. Replaces two separate estimate-based hit-test functions (one for the idle
 * track, one for the expanded panel) that could disagree with each other and with the real
 * layout — this is the single hit-test both regimes now share, always against real bounds. */
internal fun indexForTouchY(items: List<ItemBounds>, touchY: Float, fallback: Int): Int {
    if (items.isEmpty()) return fallback
    var closest = items.first().index
    var closestDist = Float.MAX_VALUE
    for (item in items) {
        val center = item.offset + item.size / 2f
        val dist = kotlin.math.abs(center - touchY)
        if (dist < closestDist) {
            closestDist = dist
            closest = item.index
        }
    }
    return closest
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PdfLabelScrollbarTest*"`
Expected: BUILD SUCCESSFUL, all tests including the four new ones pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt app/src/test/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbarTest.kt
git commit -m "feat: add pure indexForTouchY hit-test for scrollbar rows"
```

---

### Task 3: Rewrite `PdfLabelScrollbar` around a single `LazyListState`

This replaces the composable's imports and full body. It removes every estimate-based
position function (`cumulativeTopPx`, `rowFootprintPxAt`, `estimatedTotalHeightPx`,
`restingContentHeightPx`, `effectiveTrackHeightPx`, `realRestCenterByRowIndex`, `rowCenterY`,
`indexForIdleFraction`, `indexForExpandedWindowY`, `dockWindowY`) and the manual
`onGloballyPositioned`-based track-height tracking, replacing them with direct reads of
`listState.layoutInfo`. It also defers thumbnail loading until the first drag, since idle ticks
never show a bitmap. `dockScaleForDistance`, `centerOutLoadOrder`, `ScrollbarDisplayMode`,
`ScrollbarEntry`, `ItemBounds`, `indexForTouchY`, `scrollbarThumbDisposalScope`,
`PDF_LABEL_SCROLLBAR_IDLE_WIDTH`, and `PDF_LABEL_SCROLLBAR_PANEL_WIDTH` are untouched by this
task.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt`

- [ ] **Step 1: Replace the import block**

Replace lines 1-62 (package declaration through the last import) with:

```kotlin
package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

- [ ] **Step 2: Replace the `PdfLabelScrollbar` composable body**

Find the composable (its doc comment starts with `/** Right-edge scrollbar for the
continuous-scroll pane...`, function signature `internal fun PdfLabelScrollbar(...)`) and
replace the entire function, from that doc comment through its closing `}`, with:

```kotlin
/**
 * Right-edge scrollbar for the continuous-scroll pane. Idle: a thin column of tick marks, one
 * per row, with the current page's tick drawn as a highlighted pill. Dragging: the same list
 * expands into a frosted rounded panel (same style as AppScaffold's nav bar) showing every
 * page's thumbnail with its label underneath — magnified by distance from the touch position,
 * same falloff shape as macOS Dock icons. Both regimes are the SAME LazyColumn/LazyListState —
 * there is no separate estimate computing where the idle pill should rest; it reads the same
 * real layout the drag preview does, so they can never disagree about where a page actually is.
 * Thumbnails keep their real page aspect ratio once rendered (most KKC sheets are landscape) and
 * load progressively, center-out, starting only once the user first drags (idle ticks never
 * show a bitmap, so there's no reason to pay for decoding them before then).
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
    var thumbnailsRequested by remember { mutableStateOf(false) }
    val thumbCache = remember { mutableStateMapOf<Int, Bitmap?>() }

    // Minimum resting thumbnail size — barely more than a sliver, just enough to still read as
    // "a page" rather than vanishing. The focused page always gets the same full-size peak
    // (maxHeight) regardless of document length.
    val baseHeight = 2.dp
    val maxHeight = 140.dp
    val idleTickHeight = 2.dp
    val idlePillHeight = 64.dp
    val fallbackAspect = 11f / 8.5f // most KKC sheets are landscape
    val idleWidth = PDF_LABEL_SCROLLBAR_IDLE_WIDTH
    val expandedWidth = PDF_LABEL_SCROLLBAR_PANEL_WIDTH
    val rowSpacing = 5.dp
    val panelPadding = 16.dp
    // Rough per-row footprint at rest — used only to decide FULL vs. BUCKETED display mode
    // (i.e. "can every page get its own draggable/previewable row"), not to position anything.
    // Idle ticks are far smaller than this, so once bucketing makes `entries` fit this
    // footprint, idle-tick rendering fits with plenty of room to spare — the idle list never
    // needs to scroll for realistic document lengths.
    val captionAllowance = 20.dp
    val rowFootprintPx = with(density) { (baseHeight + captionAllowance + rowSpacing).toPx() }

    // Real viewport height, sourced straight from the Lazy list's own layout — replaces the
    // manually-tracked onGloballyPositioned height from before.
    val trackHeightPx = listState.layoutInfo.viewportSize.height.toFloat()

    // Adaptive display mode — mirrors github.com/mooalot/alphabetical-scroll-bar's approach of
    // dropping detail as space tightens, driven by measured space vs. page count rather than a
    // hardcoded threshold. Thumbnails never disappear — grouping into page-range buckets only
    // kicks in once even the (already-shrunk-for-this-page-count) smallest row can't fit one per
    // page, and each bucket still previews its first page's thumbnail.
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
                // The real content label (cabinet/room/etc.), never a bare number — the page
                // range is a secondary hint (rangeLabel), not the primary caption. chunk.size==1
                // is the actual "is this a single page" condition; first.page==last.page happened
                // to be equivalent only because page numbers are unique per row, which isn't a
                // guarantee this code should depend on.
                val rangeLabel = if (chunk.size == 1) null else "${first.page}–${last.page}"
                ScrollbarEntry(label = first.primaryLabel, rangeLabel = rangeLabel, page = first.page, pageRange = first.page..last.page, rowIndex = chunk.first())
            }
        } else {
            rows.mapIndexed { index, row ->
                ScrollbarEntry(label = row.primaryLabel, rangeLabel = null, page = row.page, pageRange = row.page..row.page, rowIndex = index)
            }
        }
    }
    // Reaches (visually indistinguishable from) baseHeight by 3 entries out from focus:
    // dockScaleForDistance(3, 1.3) ≈ 0.005.
    val radius = 1.3f

    val currentEntryIndex = remember(entries, currentPage) {
        entries.indexOfFirst { currentPage in it.pageRange }.let { if (it < 0) 0 else it }
    }
    val focusIndex = dragIndex ?: currentEntryIndex

    val engineCache = remember { PdfEngineCache(maxOpen = 2) }
    DisposableEffect(engineCache) {
        onDispose { scrollbarThumbDisposalScope.launch { withContext(NonCancellable) { engineCache.closeAll() } } }
    }

    // Idle ticks never show a bitmap, so there's no reason to decode any thumbnails until the
    // user actually drags for the first time.
    LaunchedEffect(entries, defaultPdfFilename, thumbnailsRequested) {
        if (!thumbnailsRequested) return@LaunchedEffect
        for (entryIndex in centerOutLoadOrder(entries.size, currentEntryIndex)) {
            if (!isActive) break
            val rowIndex = entries[entryIndex].rowIndex
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

    val animatedWidth by animateDpAsState(targetValue = if (isDragging) expandedWidth else idleWidth, label = "scrollbarWidth")

    // Keeps the current page's row anchored/visible whenever the user isn't actively dragging
    // this scrollbar themselves — mirrors the isProgrammaticScroll guard already used in
    // ContinuousReferencePdfPane.kt for the same reason (don't fight an in-progress user
    // gesture). BUCKETED mode already sizes `entries` so idle-tick rows — far smaller than the
    // drag-chip footprint bucketing is computed against — always fit inside trackHeightPx, so in
    // practice this call is a no-op safety net, not the mechanism that positions the pill: the
    // pill's real screen position always comes straight from listState.layoutInfo in the item
    // content below, not from this scroll call.
    LaunchedEffect(currentEntryIndex, isDragging) {
        if (!isDragging) listState.scrollToItem(currentEntryIndex)
    }

    fun hitTest(touchY: Float): Int = indexForTouchY(
        items = listState.layoutInfo.visibleItemsInfo.map { ItemBounds(it.index, it.offset, it.size) },
        touchY = touchY,
        fallback = focusIndex
    )

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
    val maxThumbWidthPx = with(density) { (expandedWidth - panelPadding * 2).toPx() }
    val maxThumbWidth = expandedWidth - panelPadding * 2

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(animatedWidth)
            .pointerInput(entries.size, displayMode) {
                detectTapGestures { offset -> onPageSelected(entries[hitTest(offset.y)].page) }
            }
            .pointerInput(entries.size, displayMode) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        thumbnailsRequested = true
                        val index = hitTest(offset.y)
                        dragIndex = index
                        onPageSelected(entries[index].page)
                    },
                    onVerticalDrag = { change, _ ->
                        val index = hitTest(change.position.y)
                        dragIndex = index
                        onPageSelected(entries[index].page)
                    },
                    onDragEnd = { isDragging = false; dragIndex = null },
                    onDragCancel = { isDragging = false; dragIndex = null }
                )
            }
    ) {
        if (!isDragging) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(2.dp))
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.align(Alignment.TopEnd).fillMaxSize(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(if (isDragging) rowSpacing else 0.dp),
            contentPadding = PaddingValues(vertical = if (isDragging) panelPadding else 0.dp)
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.rowIndex }) { index, entry ->
                if (!isDragging) {
                    // The current page's tick IS the pill — same list, same real layout, no
                    // separately-positioned floating widget to drift out of sync.
                    val isCurrent = index == currentEntryIndex
                    Box(
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(end = 6.dp, top = 1.dp, bottom = 1.dp)
                            .width(8.dp)
                            .height(if (isCurrent) idlePillHeight else idleTickHeight)
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                } else {
                    val distance = kotlin.math.abs(index - focusIndex)
                    val scale = dockScaleForDistance(distance, radius)
                    val target = baseHeight + (maxHeight - baseHeight) * scale
                    val animatedHeight = animateDpAsState(targetValue = target, animationSpec = tween(160), label = "rowHeight$index").value
                    val hPx = with(density) { animatedHeight.toPx() }
                    val bitmap = thumbCache[entry.rowIndex]
                    val aspect = bitmap?.let { it.width.toFloat() / it.height.toFloat().coerceAtLeast(1f) } ?: fallbackAspect
                    // Keep the box aspect-locked to the thumbnail's real aspect even when capped
                    // by the panel width — otherwise a wide-but-height-capped box leaves the
                    // image letterboxed (ContentScale.Fit gapping top/bottom) instead of filling
                    // it.
                    val uncappedWidthPx = hPx * aspect
                    val (effectiveHeightPx, effectiveWidthPx) = if (uncappedWidthPx > maxThumbWidthPx) {
                        (maxThumbWidthPx / aspect) to maxThumbWidthPx
                    } else {
                        hPx to uncappedWidthPx
                    }
                    val rowHeight = with(density) { effectiveHeightPx.toDp() }
                    val thumbWidth = with(density) { effectiveWidthPx.toDp() }
                    // Same falloff driving the thumbnail's own size — once a row has shrunk to
                    // (near enough) baseHeight, its label wouldn't be legible anyway, so it
                    // shrinks away too, down to a bare dot instead of unreadable text.
                    val showLabel = scale > 0.15f

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
                                            if (index == currentEntryIndex) {
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
                                        style = if (index == focusIndex) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        maxLines = if (index == focusIndex) 2 else 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (entry.rangeLabel != null) {
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
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(3.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.onSurfaceVariant)
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
```

- [ ] **Step 3: Compile**

Run: `./gradlew.bat :app:compileDebugKotlin -q`
Expected: no output (clean compile). If there are unresolved-reference errors, check for a
leftover use of a removed symbol (`dockWindowY`, `trackHeightPx` as a `mutableFloatStateOf`,
`rowCenterY`, `realRestCenterByRowIndex`, `cumulativeTopPx`, `rowFootprintPxAt`,
`estimatedTotalHeightPx`, `restingContentHeightPx`, `effectiveTrackHeightPx`,
`indexForIdleFraction`, `indexForExpandedWindowY`) that Step 2 should have replaced.

- [ ] **Step 4: Run the full scrollbar test suite**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PdfLabelScrollbarTest*"`
Expected: BUILD SUCCESSFUL — `dockScaleForDistance`, `centerOutLoadOrder`, and
`indexForTouchY` tests all still pass (none of those functions changed in this task).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt
git commit -m "$(cat <<'EOF'
refactor: unify scrollbar idle pill and drag preview on one LazyListState

The idle pill and the drag preview used to be two independently
computed position models (a hand-tuned cumulative-height estimate vs.
real Compose layout), which kept drifting apart despite incremental
fixes. Both now read the same LazyListState.layoutInfo, so there's no
second model left to disagree with the first.
EOF
)"
```

---

### Task 4: Build, install, and verify on-device

**Files:** none (build/verification only).

- [ ] **Step 1: Build the release APK**

Run: `./gradlew.bat :app:assembleRelease -q`
Expected: no output (clean build). Output: `app/build/outputs/apk/release/app-release.apk`.

- [ ] **Step 2: Confirm a tablet is connected**

Run: `adb devices`
Expected: at least one line ending in `device` (not `unauthorized`/`offline`).

- [ ] **Step 3: Install, preserving app data**

Run: `adb install -r "app/build/outputs/apk/release/app-release.apk"`
Expected: `Success`.

- [ ] **Step 4: Manually verify on the tablet**

Open a job with a long PDF reference document in continuous-scroll mode. Perform, in order:

1. Confirm the idle view shows a thin tick column with one highlighted pill-shaped tick — no
   crash, no blank scrollbar.
2. Drag the scrollbar down to roughly the middle of a long document, watch the expanded panel
   magnify thumbnails as you drag, and release.
3. Confirm the page that opens matches the page the panel was highlighting at release — no
   jump/stutter during the drag, no mismatch on release.
4. Immediately drag again from the new resting position to a different depth (near the top,
   then near the bottom) and release each time, confirming the same page-match holds at every
   depth, not just the first drag.
5. Tap directly on the idle tick column (no drag) at a few different heights; confirm each tap
   navigates to a page in the expected vicinity.
6. Rotate/resize if the app supports it (split-pane Assembly view), confirming the scrollbar
   still tracks correctly at the narrower width.

If any check fails, capture what specifically diverged (which step, what was expected vs.
observed) — that's Phase 1 evidence for a follow-up systematic-debugging pass, not something to
patch blind.

- [ ] **Step 5: Final commit if on-device testing required tweaks**

Only if Step 4 surfaced a fix: stage the specific files changed, commit with a message
describing what on-device behavior was wrong and what changed. Skip this step entirely if Step
4 passed clean.
