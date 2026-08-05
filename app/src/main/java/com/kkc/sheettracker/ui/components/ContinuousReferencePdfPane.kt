package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.ui.markup.PdfMarkupOverlay
import com.kkc.sheettracker.ui.markup.PdfMarkupToolState
import com.kkc.sheettracker.ui.viewer.ResolvedPageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun computeRenderWindow(
    firstVisiblePage: Int,
    lastVisiblePage: Int,
    totalPages: Int,
    buffer: Int = 1
): IntRange {
    if (totalPages <= 0) return IntRange.EMPTY
    val start = (firstVisiblePage - buffer).coerceIn(1, totalPages)
    val end = (lastVisiblePage + buffer).coerceIn(1, totalPages)
    return start..end
}

internal fun <T> lruTouch(order: List<T>, key: T): List<T> =
    order.filterNot { it == key } + key

internal fun <T> lruEvictionCandidates(order: List<T>, maxOpen: Int): List<T> =
    if (order.size <= maxOpen) emptyList() else order.take(order.size - maxOpen)

/**
 * Keeps at most [maxOpen] [PdfRenderEngine]s open at once, keyed by absolute file path.
 * Continuous-mode pages usually share one file; this only matters for Assembly's
 * FF/FL virtual-mapping case where a handful of pages point at a different file.
 *
 * All access goes through [mutex]: [get] performs its LRU touch, lookup/construction,
 * and eviction as one atomic critical section, so `engines.size` never exceeds [maxOpen]
 * for longer than the instant between insert and eviction within that same section.
 * Safe to call [get] concurrently from multiple coroutines (e.g. one per visible page).
 */
internal class PdfEngineCache(
    private val maxOpen: Int = 3
) {
    private val mutex = Mutex()
    private val engines = linkedMapOf<String, PdfRenderEngine>()
    private var order = listOf<String>()

    suspend fun get(file: java.io.File): PdfRenderEngine = mutex.withLock {
        val key = file.absolutePath
        order = lruTouch(order, key)
        engines[key]?.let { return@withLock it }
        val engine = PdfRenderEngine(file)
        engines[key] = engine
        evictLocked()
        engine
    }

    suspend fun closeAll() = mutex.withLock {
        engines.values.forEach { it.close() }
        engines.clear()
        order = emptyList()
    }

    /** Must only be called while holding [mutex]. */
    private suspend fun evictLocked() {
        val evicted = lruEvictionCandidates(order, maxOpen)
        evicted.forEach { key ->
            engines.remove(key)?.close()
        }
        order = order.filterNot { it in evicted }
    }
}

// Dedicated, non-composition-scoped coroutine scope for closing [PdfRenderEngine] instances
// owned by a [PdfEngineCache]. Same rationale as ReferencePdfPane's disposal scope (see comment
// there): DisposableEffect's onDispose runs synchronously and cannot suspend, and
// rememberCoroutineScope's scope may already be cancelled by the time onDispose runs. This is a
// separate instance from ReferencePdfPane's because that one is declared `private` at file scope,
// which in Kotlin is file-private (not package-private) and therefore not visible here.
private val continuousPdfEngineDisposalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Per-page pinch-to-zoom that cooperates with the surrounding Lazy list scroll: a single
 * finger is left UNCONSUMED (so the list's own scrollable() keeps handling ordinary swipes)
 * unless the page is already zoomed in, in which case a single finger pans the zoomed image
 * instead. Two or more fingers always claim the gesture for pinch-zoom.
 *
 * While zoomed, re-renders a sharp high-res crop of the current viewport (same
 * [PdfRenderEngine.renderViewportTile] mechanism the single-page viewer uses) 120ms after the
 * gesture settles, so zoomed-in pages aren't just an upscaled blur of the base bitmap.
 */
@Composable
private fun ZoomablePageImage(
    bitmap: Bitmap?,
    contentDescription: String,
    gesturesEnabled: Boolean,
    engineCache: PdfEngineCache,
    file: java.io.File?,
    pageIndex: Int,
    matteColorArgb: Int,
    modifier: Modifier = Modifier
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var isInteracting by remember { mutableStateOf(false) }
    var detailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val minZoom = 1f
    val maxZoom = 6f

    fun clampPan(targetZoom: Float, x: Float, y: Float): Pair<Float, Float> {
        if (viewSize == IntSize.Zero || targetZoom <= minZoom) return 0f to 0f
        val vw = viewSize.width.toFloat().coerceAtLeast(1f)
        val vh = viewSize.height.toFloat().coerceAtLeast(1f)
        val maxPanX = ((vw * targetZoom - vw) / 2f).coerceAtLeast(0f)
        val maxPanY = ((vh * targetZoom - vh) / 2f).coerceAtLeast(0f)
        return x.coerceIn(-maxPanX, maxPanX) to y.coerceIn(-maxPanY, maxPanY)
    }

    LaunchedEffect(zoom, panX, panY, isInteracting, file, viewSize) {
        if (file == null || isInteracting || zoom <= 1.02f || viewSize == IntSize.Zero) {
            if (zoom <= 1.02f) detailBitmap = null
            return@LaunchedEffect
        }
        delay(120)
        if (isInteracting) return@LaunchedEffect
        val viewport = PdfViewportState(zoom = zoom, panX = panX, panY = panY, viewSize = viewSize)
        detailBitmap = withContext(Dispatchers.IO) {
            engineCache.get(file).renderViewportTile(pageIndex, viewport, matteColorArgb)
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewSize = it }
            .then(
                if (gesturesEnabled) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pointerCount = event.changes.count { it.pressed }
                                val claim = pointerCount >= 2 || zoom > 1.02f
                                if (claim) {
                                    isInteracting = true
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val nextZoom = (zoom * zoomChange).coerceIn(minZoom, maxZoom)
                                    val appliedZoomChange = if (zoom == 0f) 1f else nextZoom / zoom
                                    val anchorX = if (centroid.isSpecified) centroid.x - viewSize.width / 2f else 0f
                                    val anchorY = if (centroid.isSpecified) centroid.y - viewSize.height / 2f else 0f
                                    val nextPanX = panX * appliedZoomChange + panChange.x + anchorX * (1f - appliedZoomChange)
                                    val nextPanY = panY * appliedZoomChange + panChange.y + anchorY * (1f - appliedZoomChange)
                                    val (cx, cy) = clampPan(nextZoom, nextPanX, nextPanY)
                                    zoom = nextZoom
                                    panX = cx
                                    panY = cy
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            isInteracting = false
                            if (zoom <= 1.02f) {
                                zoom = 1f
                                panX = 0f
                                panY = 0f
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panX
                        translationY = panY
                    },
                contentScale = ContentScale.Fit
            )
            // Sharp high-res crop of the current viewport, drawn at full size on top of the
            // scaled (and therefore softening) base bitmap once it's ready — same layering
            // idea as the single-page viewer's base+detail bitmaps.
            val currentDetail = detailBitmap
            if (currentDetail != null && zoom > 1.02f) {
                Image(
                    bitmap = currentDetail.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

@Composable
internal fun ContinuousReferencePdfPane(
    modifier: Modifier = Modifier,
    orientation: androidx.compose.foundation.gestures.Orientation,
    totalPages: Int,
    resolvePage: (Int) -> ResolvedPageSource,
    pdfFileForFilename: (String) -> java.io.File?,
    fileIdentitySeed: Long = 0L,
    preferDarkMode: Boolean = false,
    onCenteredPageChange: (Int) -> Unit,
    scrollToPage: Int,
    markupEnabled: Boolean = false,
    markupToolState: PdfMarkupToolState? = null,
    markupStrokesForPage: (sourceFilename: String, sourcePage: Int) -> List<PdfInkStroke> = { _, _ -> emptyList() },
    onMarkupStrokeAdded: ((sourceFilename: String, sourcePage: Int, PdfInkStroke) -> Unit)? = null,
    onMarkupStrokeErased: ((sourceFilename: String, sourcePage: Int, strokeId: String) -> Unit)? = null
) {
    val engineCache = remember(fileIdentitySeed) { PdfEngineCache(maxOpen = 3) }
    DisposableEffect(engineCache) {
        onDispose { continuousPdfEngineDisposalScope.launch { withContext(NonCancellable) { engineCache.closeAll() } } }
    }

    val listState = rememberLazyListState()
    val currentOnCenteredPageChange by rememberUpdatedState(onCenteredPageChange)

    LaunchedEffect(scrollToPage, totalPages) {
        // Only jump for an EXTERNAL request (initial open, scrollbar drag, resume-to-page).
        // scrollToPage is also fed by this list's OWN onCenteredPageChange reporting further
        // up the tree, so without these guards every ordinary scroll would re-trigger a
        // scrollToItem back to page-alignment — fighting the user's drag and feeling like
        // forced pagination instead of free scroll.
        if (totalPages <= 0 || scrollToPage !in 1..totalPages) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect
        val current = listState.firstVisibleItemIndex + 1
        if (current == scrollToPage) return@LaunchedEffect
        // Animated, not an instant cut — external jumps (scrollbar drag, resume-to-page)
        // should read as a smooth scroll, not a series of hard snaps.
        listState.animateScrollToItem((scrollToPage - 1).coerceIn(0, totalPages - 1))
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) null else visible.first().index + 1
        }
            .distinctUntilChanged()
            .collectLatest { centeredPage -> if (centeredPage != null) currentOnCenteredPageChange(centeredPage) }
    }

    val renderWindow by remember(totalPages) {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) {
                IntRange.EMPTY
            } else {
                computeRenderWindow(
                    firstVisiblePage = visible.first().index + 1,
                    lastVisiblePage = visible.last().index + 1,
                    totalPages = totalPages,
                    buffer = 1
                )
            }
        }
    }

    val pageContent: @Composable (Int) -> Unit = { displayPage ->
        val resolved = remember(displayPage, fileIdentitySeed) { resolvePage(displayPage) }
        val file = remember(resolved.pdfFilename, fileIdentitySeed) { pdfFileForFilename(resolved.pdfFilename) }
        val inWindow = displayPage in renderWindow
        var bitmap by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf<Bitmap?>(null) }
        var aspectRatio by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf<Float?>(null) }
        // Real box size, needed so PdfMarkupOverlay's page transform is valid — with
        // viewSize left at IntSize.Zero, currentTransform() returns null and the overlay's
        // pointerInteropFilter bails out before ever registering a stroke.
        var boxSize by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        val matteColorArgb = if (preferDarkMode) MaterialTheme.colorScheme.surface.toArgb() else android.graphics.Color.WHITE

        LaunchedEffect(displayPage, resolved, file, fileIdentitySeed) {
            if (file == null) return@LaunchedEffect
            aspectRatio = withContext(Dispatchers.IO) {
                engineCache.get(file).pageAspectRatio((resolved.sourcePage - 1).coerceAtLeast(0))
            }
        }

        LaunchedEffect(displayPage, resolved, file, inWindow, matteColorArgb, fileIdentitySeed) {
            if (file == null || !inWindow) return@LaunchedEffect
            if (bitmap != null) return@LaunchedEffect
            // Renders as soon as a page enters the small render window, including while
            // actively scrolling — real pages are what make continuous scroll usable, not
            // gray placeholders. The window itself (visible ± 1 buffer) is what keeps this
            // bounded, and the bitmap != null guard above stops re-render on every frame.
            val viewSize = androidx.compose.ui.unit.IntSize(1080, 1400)
            bitmap = withContext(Dispatchers.IO) {
                engineCache.get(file).renderBasePage(
                    pageIndex = (resolved.sourcePage - 1).coerceAtLeast(0),
                    viewSize = viewSize,
                    matteColorArgb = matteColorArgb
                )
            }
        }

        // markupStrokesForPage already scopes visibility (markupStrokesVisible + centered
        // page match) independent of markupEnabled — gating on markupEnabled here as well
        // would make drawn strokes vanish the instant the pen is toggled off.
        val strokes = markupStrokesForPage(resolved.pdfFilename, resolved.sourcePage)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio ?: (8.5f / 11f))
                .onSizeChanged { boxSize = it }
        ) {
            val currentBitmap = bitmap
            if (currentBitmap != null) {
                ZoomablePageImage(
                    bitmap = currentBitmap,
                    contentDescription = "Page $displayPage",
                    gesturesEnabled = !markupEnabled,
                    engineCache = engineCache,
                    file = file,
                    pageIndex = (resolved.sourcePage - 1).coerceAtLeast(0),
                    matteColorArgb = matteColorArgb,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
            if (markupToolState != null && onMarkupStrokeAdded != null && onMarkupStrokeErased != null &&
                (markupEnabled || strokes.isNotEmpty())
            ) {
                PdfMarkupOverlay(
                    modifier = Modifier.fillMaxSize(),
                    viewportState = PdfViewportState(zoom = 1f, panX = 0f, panY = 0f, viewSize = boxSize),
                    pageAspectRatio = aspectRatio,
                    activeStrokes = strokes,
                    inputEnabled = markupEnabled,
                    activeTool = markupToolState.activeTool,
                    activeColor = markupToolState.activeColor,
                    activeThickness = markupToolState.activeThickness,
                    allowFingerDrawing = markupToolState.allowFingerDrawing,
                    onStylusButtonEraserChanged = { markupToolState.isStylusButtonEraserActive = it },
                    onStrokeAdded = { stroke -> onMarkupStrokeAdded(resolved.pdfFilename, resolved.sourcePage, stroke) },
                    onStrokeErased = { strokeId -> onMarkupStrokeErased(resolved.pdfFilename, resolved.sourcePage, strokeId) }
                )
            }
        }
    }

    // Markup drawing pins the list to the current page — the pen and the list scroll
    // gesture must not fight each other, same rule ReferencePdfPane applies today.
    val userScrollEnabled = !markupEnabled

    if (orientation == androidx.compose.foundation.gestures.Orientation.Vertical) {
        LazyColumn(
            modifier = modifier,
            state = listState,
            userScrollEnabled = userScrollEnabled,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(count = totalPages, key = { it + 1 }) { index -> pageContent(index + 1) }
        }
    } else {
        LazyRow(
            modifier = modifier,
            state = listState,
            userScrollEnabled = userScrollEnabled,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(count = totalPages, key = { it + 1 }) { index ->
                Box(Modifier.fillParentMaxWidth()) { pageContent(index + 1) }
            }
        }
    }
}
