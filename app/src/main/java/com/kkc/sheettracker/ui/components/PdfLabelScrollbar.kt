package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
import kotlin.math.roundToInt

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

/** Maps a touch fraction along the idle (collapsed) track to a row index — uniform spacing,
 * used only before the expanded dock has laid out real (unevenly magnified) row positions. */
internal fun idleIndexForFraction(rowCount: Int, fraction: Float): Int {
    if (rowCount <= 0) return 0
    if (rowCount == 1) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    return (clamped * (rowCount - 1)).roundToInt().coerceIn(0, rowCount - 1)
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
 * Right-edge scrollbar for the continuous-scroll pane. Idle: a thin track with a positioned
 * thumb (just a "you are here" indicator). Dragging: pops out into a frosted rounded panel (same
 * style as AppScaffold's nav bar) showing every page's thumbnail with its label underneath —
 * magnified by distance from the touch position, same falloff shape as macOS Dock icons.
 * Thumbnails keep their real page aspect ratio once rendered (most KKC sheets are landscape) and
 * load progressively, center-out, so opening a long document doesn't stall on rendering every
 * page.
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
    val scrollState = rememberScrollState()
    var isDragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    val thumbCache = remember { mutableStateMapOf<Int, Bitmap?>() }
    val rowCenterY = remember { mutableStateMapOf<Int, Float>() }
    var dockWindowY by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    // Real (measured, not estimated) resting center Y for a row, captured the moment a drag
    // releases on it — keyed by rowIndex (stable page identity, unlike `entries` index which
    // shifts when bucket boundaries recompute). The idle pill prefers this over the cumulative
    // estimate below whenever it's available: the estimate assumes every row costs the same
    // caption-chrome space, but most rows (far from focus) render only a bare dot, not a
    // caption block, so the estimate inflates them and skews the resting fraction toward the
    // bottom — confirmed on-device (real center 927px vs estimated-fraction pill position
    // ~1370px on a 2664px track). A row we've actually dragged to and released on has a known
    // real position; trust that over the guess.
    val realRestCenterByRowIndex = remember { mutableStateMapOf<Int, Float>() }

    // Minimum resting thumbnail size — barely more than a sliver, just enough to still read as
    // "a page" rather than vanishing. The focused page always gets the same full-size peak
    // (maxHeight) regardless of document length.
    val baseHeight = 2.dp
    val maxHeight = 140.dp
    val fallbackAspect = 11f / 8.5f // most KKC sheets are landscape
    val idleWidth = PDF_LABEL_SCROLLBAR_IDLE_WIDTH
    val expandedWidth = PDF_LABEL_SCROLLBAR_PANEL_WIDTH
    val rowSpacing = 5.dp
    val panelPadding = 16.dp
    // Rough per-row footprint at rest (thumbnail + caption + its own padding) — used only to
    // estimate scroll position / travel range / display mode before real layout exists. Real
    // layout (rowCenterY) takes over once available.
    val captionAllowance = 20.dp
    val spacingPx = with(density) { rowSpacing.toPx() }
    val panelPaddingPx = with(density) { panelPadding.toPx() }
    val rowFootprintPx = with(density) { (baseHeight + captionAllowance).toPx() } + spacingPx

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

    // Single source of truth for "where does row i's top edge / height land if `focus` were the
    // magnified row" — the exact same dock-bulge math the real dragging layout uses below. The
    // idle pill position and the drag-start jump-to-touched-page estimate both use this too, so
    // they can never disagree with where a page actually ends up in the expanded panel (which is
    // what caused the scrollbar-vs-preview-location mismatch: they used to assume flat, uniform
    // row heights while the real panel is deliberately non-uniform).
    fun rowHeightPxAt(index: Int, focus: Int): Float {
        val scale = dockScaleForDistance(kotlin.math.abs(index - focus), radius)
        return with(density) { (baseHeight + (maxHeight - baseHeight) * scale).toPx() }
    }
    // rowHeightPxAt is just the thumbnail's own height (correct for sizing the thumbnail Box
    // itself). Each rendered row is a whole chip though — thumbnail + caption text + its own
    // padding/margin — so anywhere this is used to estimate POSITION (cumulative Y, row centers
    // for hit-testing), it must include that chrome or the estimate undershoots the real layout.
    // That undershoot was the actual cause of the scrollbar/preview mismatch persisting after the
    // first fix, and of the drag range collapsing to only the first ~13 entries: effectiveTrackHeightPx
    // (derived from this sum) came out far shorter than the real scrollable content, so dragging to
    // the bottom of the track only ever mapped to however far that undershot total reached.
    val rowChromeAllowancePx = with(density) { (captionAllowance + 12.dp + 6.dp).toPx() }
    fun rowFootprintPxAt(index: Int, focus: Int): Float = rowHeightPxAt(index, focus) + rowChromeAllowancePx
    fun cumulativeTopPx(targetIndex: Int, focus: Int): Float {
        var y = panelPaddingPx
        for (i in 0 until targetIndex) {
            y += rowFootprintPxAt(i, focus) + spacingPx
        }
        return y
    }
    fun estimatedTotalHeightPx(focus: Int): Float {
        if (entries.isEmpty()) return 0f
        return (cumulativeTopPx(entries.size, focus) - spacingPx + panelPaddingPx).coerceAtLeast(0f)
    }

    // Resting (bulge-aware) stack height, bulge centered on the CURRENT page — the panel never
    // reaches beyond this when the document is short, so the idle thumb / drag hit-test shouldn't
    // be reachable past it either.
    val restingContentHeightPx = estimatedTotalHeightPx(currentEntryIndex)
    val effectiveTrackHeightPx = if (trackHeightPx > 0f) {
        kotlin.math.min(trackHeightPx, restingContentHeightPx)
    } else {
        trackHeightPx
    }

    val engineCache = remember { PdfEngineCache(maxOpen = 2) }
    DisposableEffect(engineCache) {
        onDispose { scrollbarThumbDisposalScope.launch { withContext(NonCancellable) { engineCache.closeAll() } } }
    }

    LaunchedEffect(entries, defaultPdfFilename) {
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

    fun indexForExpandedWindowY(touchY: Float): Int {
        if (rowCenterY.isEmpty()) return focusIndex
        var closest = focusIndex
        var closestDist = Float.MAX_VALUE
        rowCenterY.forEach { (index, centerY) ->
            val dist = kotlin.math.abs(centerY - touchY)
            if (dist < closestDist) {
                closestDist = dist
                closest = index
            }
        }
        return closest
    }

    // Inverse of the idle pill's own position math (see the idle branch below) — given a touch
    // fraction along the idle track, find which entry's bulge-aware center lands closest to that
    // Y. Uses currentEntryIndex as the assumed focus because that's what the idle track itself is
    // drawn against; this is what makes tapping/starting a drag land on the page you'd expect from
    // where the idle pill visually sits.
    fun indexForIdleFraction(fraction: Float): Int {
        if (entries.isEmpty()) return 0
        val targetY = fraction.coerceIn(0f, 1f) * effectiveTrackHeightPx
        var closest = 0
        var closestDist = Float.MAX_VALUE
        for (i in entries.indices) {
            val centerY = cumulativeTopPx(i, currentEntryIndex) + rowFootprintPxAt(i, currentEntryIndex) / 2f
            val dist = kotlin.math.abs(centerY - targetY)
            if (dist < closestDist) {
                closestDist = dist
                closest = i
            }
        }
        return closest
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(animatedWidth)
            .onGloballyPositioned {
                dockWindowY = it.positionInWindow().y
                if (!isDragging) trackHeightPx = it.size.height.toFloat()
            }
            .pointerInput(entries.size, displayMode) {
                detectTapGestures { offset ->
                    val fraction = if (effectiveTrackHeightPx > 0f) offset.y / effectiveTrackHeightPx else 0f
                    val index = indexForIdleFraction(fraction)
                    onPageSelected(entries[index].page)
                }
            }
            .pointerInput(entries.size, displayMode) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val fraction = if (effectiveTrackHeightPx > 0f) offset.y / effectiveTrackHeightPx else 0f
                        val index = indexForIdleFraction(fraction)
                        dragIndex = index
                        onPageSelected(entries[index].page)
                        // Initial scroll so the touched page starts near the finger instead of
                        // the panel always opening scrolled to the top — estimated with the SAME
                        // bulge math the real layout below uses (touched row becomes the new
                        // focus), not a flat per-row guess.
                        val estimatedCenterY = cumulativeTopPx(index, index) + rowFootprintPxAt(index, index) / 2f
                        val estimatedY = estimatedCenterY - offset.y
                        scrollState.dispatchRawDelta(estimatedY - scrollState.value)
                    },
                    onVerticalDrag = { change, _ ->
                        val index = indexForExpandedWindowY(change.position.y)
                        dragIndex = index
                        onPageSelected(entries[index].page)
                    },
                    onDragEnd = {
                        // Snapshot the real measured center of wherever the drag actually
                        // released, before rowCenterY is wiped — this is what makes the idle
                        // pill land exactly back where the preview showed instead of an
                        // estimate-derived guess (see realRestCenterByRowIndex above).
                        dragIndex?.let { idx ->
                            rowCenterY[idx]?.let { center -> realRestCenterByRowIndex[entries[idx].rowIndex] = center }
                        }
                        isDragging = false; dragIndex = null; rowCenterY.clear()
                    },
                    onDragCancel = {
                        dragIndex?.let { idx ->
                            rowCenterY[idx]?.let { center -> realRestCenterByRowIndex[entries[idx].rowIndex] = center }
                        }
                        isDragging = false; dragIndex = null; rowCenterY.clear()
                    }
                )
            }
    ) {
        if (isDragging) {
            // Compute every row's CURRENT animated height first — the same values drive both
            // the panel's own sizing and the actual row sizing below, so they never drift out of
            // sync with each other. Every entry — bucketed or not — gets a real thumbnail; only
            // the size and the label (single page vs. "12–18" range) differ.
            val animatedHeightsPx = entries.indices.map { index ->
                val distance = kotlin.math.abs(index - focusIndex)
                val scale = dockScaleForDistance(distance, radius)
                val target = baseHeight + (maxHeight - baseHeight) * scale
                val animated = animateDpAsState(targetValue = target, animationSpec = tween(160), label = "rowHeight$index").value
                with(density) { animated.toPx() }
            }
            val paddingPx = panelPaddingPx
            val maxThumbWidthPx = with(density) { (expandedWidth - panelPadding * 2).toPx() }

            var accY = paddingPx
            val widthsPx = ArrayList<Float>(entries.size)
            val effectiveHeightsPx = ArrayList<Float>(entries.size)
            for (index in entries.indices) {
                val h = animatedHeightsPx[index]
                val bitmap = thumbCache[entries[index].rowIndex]
                val aspect = bitmap?.let { it.width.toFloat() / it.height.toFloat().coerceAtLeast(1f) } ?: fallbackAspect
                // Keep the box aspect-locked to the thumbnail's real aspect even when capped by
                // the panel width — otherwise a wide-but-height-capped box leaves the image
                // letterboxed (ContentScale.Fit gapping top/bottom) instead of filling it.
                val uncappedWidth = h * aspect
                val (effectiveHeight, effectiveWidth) = if (uncappedWidth > maxThumbWidthPx) {
                    (maxThumbWidthPx / aspect) to maxThumbWidthPx
                } else {
                    h to uncappedWidth
                }
                widthsPx += effectiveWidth
                effectiveHeightsPx += effectiveHeight
                accY += effectiveHeight + spacingPx
            }

            // Per-row frosted chips instead of one big panel Surface — each chip's backdrop is
            // sized to exactly wrap ITS OWN already-correct box (widthsPx/effectiveHeightsPx
            // above), so it always hugs the current bulge with zero risk of clipping a thumbnail's
            // corners (a single continuous "blob" outline through per-row centers did that: the
            // curve only matched a row's width exactly at its vertical center, so a sharply
            // bulging row's top/bottom corners got sliced by the curve on its way to the next
            // row's much-smaller width). Mirrors AppScaffold's frosted nav bar per chip: a Surface
            // using its own shadowElevation param, untinted surface color, transparent when haze
            // is actually compositing so the blur alone shows through.
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

            val trackHeightDp = with(density) { trackHeightPx.toDp() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(expandedWidth)
                    // Wraps real content height (short doc → compact panel) instead of always
                    // filling the reserved track — only caps out and scrolls once content
                    // actually runs past the available space.
                    .heightIn(max = trackHeightDp)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .fillMaxWidth()
                        .padding(top = panelPadding, bottom = panelPadding),
                    horizontalAlignment = Alignment.End
                ) {
                    entries.forEachIndexed { index, entry ->
                        val rowHeight = with(density) { effectiveHeightsPx[index].toDp() }
                        val thumbWidth = with(density) { widthsPx[index].toDp() }
                        val maxThumbWidth = expandedWidth - panelPadding * 2
                        val bitmap = thumbCache[entry.rowIndex]
                        // Same falloff driving the thumbnail's own size — once a row has shrunk
                        // to (near enough) baseHeight, its label wouldn't be legible anyway, so it
                        // shrinks away too, down to a bare dot instead of unreadable text.
                        val scale = dockScaleForDistance(kotlin.math.abs(index - focusIndex), radius)
                        val showLabel = scale > 0.15f

                        androidx.compose.material3.Surface(
                            modifier = Modifier
                                .padding(vertical = 3.dp, horizontal = panelPadding)
                                .onGloballyPositioned { coords ->
                                    rowCenterY[index] = coords.positionInWindow().y + coords.size.height / 2f - dockWindowY
                                },
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
        } else {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(2.dp))
            )
            val thumbHeightDp = 64.dp
            val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
            // realRestCenterByRowIndex holds the actually-measured center from the last drag
            // release onto this row — use it directly (bypassing the estimate, and the
            // estimate-clamped effectiveTrackHeightPx) whenever we have it, since it's ground
            // truth for exactly where the preview showed this page. Falls back to the
            // bulge-aware cumulative estimate (same model the drag-start hit-test inverts) only
            // for a row we've never actually dragged to.
            val realCenterPx = realRestCenterByRowIndex[entries[currentEntryIndex].rowIndex]
            val thumbOffsetPx = if (realCenterPx != null && trackHeightPx > 0f) {
                (realCenterPx - thumbHeightPx / 2f).coerceIn(0f, (trackHeightPx - thumbHeightPx).coerceAtLeast(0f))
            } else {
                val idleFraction = if (restingContentHeightPx > 0f) {
                    val centerPx = cumulativeTopPx(currentEntryIndex, currentEntryIndex) +
                        rowFootprintPxAt(currentEntryIndex, currentEntryIndex) / 2f
                    (centerPx / restingContentHeightPx).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val thumbTravel = (effectiveTrackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                idleFraction * thumbTravel
            }
            val thumbOffsetDp = with(density) { thumbOffsetPx.toDp() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 6.dp)
                    .offset(y = thumbOffsetDp)
                    .width(8.dp)
                    .height(thumbHeightDp)
                    .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(4.dp))
            )
        }
    }
}
