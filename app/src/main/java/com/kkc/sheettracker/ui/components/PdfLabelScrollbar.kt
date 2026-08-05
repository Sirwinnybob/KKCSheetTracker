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

/** Falloff for the dock-style magnification — smooth gaussian taper across neighbors. */
internal fun dockScaleForDistance(distance: Int, radius: Float): Float {
    if (radius <= 0f) return if (distance == 0) 1f else 0f
    val t = distance.toDouble() / radius.toDouble()
    return kotlin.math.exp(-(t * t)).toFloat()
}

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

/**
 * How much detail the scrollbar can afford to show, derived from available vertical space vs.
 * page count — never hardcoded to a specific count. Mirrors the degrade pattern in
 * github.com/mooalot/alphabetical-scroll-bar (which drops letters as space tightens): thumbnails
 * always stay (every entry — including bucketed ranges — still shows a real page preview, just
 * shrunk), we only group multiple pages under one entry once even the smallest usable thumbnail
 * size can't fit one row per page.
 */
private enum class ScrollbarDisplayMode { FULL, BUCKETED }

/** One scrollbar row. In FULL mode this is exactly one page; in BUCKETED mode it represents a
 * contiguous page range collapsed into a single entry, previewed by its first page's thumbnail
 * and labeled with that page's REAL content label (not a bare number) — [rangeLabel] carries the
 * page-range ("12–18") as a secondary hint, non-null only when the entry spans more than one page. */
private data class ScrollbarEntry(
    val label: String,
    val rangeLabel: String?,
    val page: Int,
    val pageRange: IntRange,
    val rowIndex: Int
)

private val scrollbarThumbDisposalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Reserved space when idle (collapsed) — just enough for a thin track + thumb. */
internal val PDF_LABEL_SCROLLBAR_IDLE_WIDTH = 36.dp

/**
 * Expanded dock panel width while dragging — thumbnails magnify WITHIN this width (growing
 * taller, capped narrower). The panel overlays the PDF content transiently while dragging;
 * only [PDF_LABEL_SCROLLBAR_IDLE_WIDTH] is permanently reserved (see [UnifiedReferenceViewer]'s
 * contentPadding for the continuous branch).
 */
internal val PDF_LABEL_SCROLLBAR_PANEL_WIDTH = 240.dp

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
