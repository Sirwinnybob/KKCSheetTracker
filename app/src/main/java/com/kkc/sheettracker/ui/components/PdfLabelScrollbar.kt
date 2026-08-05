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
import androidx.compose.foundation.layout.height
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
internal val PDF_LABEL_SCROLLBAR_IDLE_WIDTH = 20.dp

/**
 * Expanded dock panel width while dragging — thumbnails magnify WITHIN this width (growing
 * taller, capped narrower). The panel overlays the PDF content transiently while dragging;
 * only [PDF_LABEL_SCROLLBAR_IDLE_WIDTH] is permanently reserved (see [UnifiedReferenceViewer]'s
 * contentPadding for the continuous branch).
 */
internal val PDF_LABEL_SCROLLBAR_PANEL_WIDTH = 240.dp

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
