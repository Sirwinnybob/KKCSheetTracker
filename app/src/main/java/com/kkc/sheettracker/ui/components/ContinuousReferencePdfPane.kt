package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.kkc.sheettracker.data.models.PdfInkStroke
import kotlinx.coroutines.Job
import com.kkc.sheettracker.ui.markup.PdfMarkupOverlay
import com.kkc.sheettracker.ui.markup.PdfMarkupToolState
import com.kkc.sheettracker.ui.viewer.ResolvedPageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.foundation.MutatePriority
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val CONTINUOUS_MAX_ZOOM = 10f
private const val CONTINUOUS_MIN_ZOOM = 1f

internal data class FlingStepResult(
    val nextVelocity: Float,
    val delta: Float
)

internal fun computeFlingStep(
    velocity: Float,
    dtSeconds: Float,
    friction: Float = 2.5f,
    minVelocityThreshold: Float = 10f
): FlingStepResult {
    if (abs(velocity) <= minVelocityThreshold) {
        return FlingStepResult(0f, 0f)
    }
    val decayFactor = kotlin.math.exp(-friction * dtSeconds)
    val nextVelocity = velocity * decayFactor
    val delta = velocity * (1f - decayFactor) / friction
    return FlingStepResult(
        nextVelocity = if (abs(nextVelocity) <= minVelocityThreshold) 0f else nextVelocity,
        delta = delta
    )
}

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

/** A sub-rectangle of a page, expressed as fractions (0..1) of that page's own width/height. */
internal data class UnitRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Given where a page is currently drawn on screen (already reflecting the shared whole-stack
 * zoom + pan) and the pane's own visible viewport, returns which fraction of that PAGE (local to
 * its own bounds, 0..1 each axis) is actually on screen right now. Null if there's no overlap.
 *
 * This is what lets continuous-scroll zoom re-render only the on-screen slice of a page instead
 * of the whole page, even though the zoom is shared across every page in the stack.
 */
internal fun visiblePageFraction(
    pageLeft: Float,
    pageTop: Float,
    pageRight: Float,
    pageBottom: Float,
    viewportWidth: Float,
    viewportHeight: Float
): UnitRect? {
    val ix0 = max(pageLeft, 0f)
    val iy0 = max(pageTop, 0f)
    val ix1 = min(pageRight, viewportWidth)
    val iy1 = min(pageBottom, viewportHeight)
    if (ix1 <= ix0 || iy1 <= iy0) return null
    val pw = (pageRight - pageLeft).coerceAtLeast(0.001f)
    val ph = (pageBottom - pageTop).coerceAtLeast(0.001f)
    return UnitRect(
        left = ((ix0 - pageLeft) / pw).coerceIn(0f, 1f),
        top = ((iy0 - pageTop) / ph).coerceIn(0f, 1f),
        right = ((ix1 - pageLeft) / pw).coerceIn(0f, 1f),
        bottom = ((iy1 - pageTop) / ph).coerceIn(0f, 1f)
    )
}

/** Max cross-axis pan (screen px) allowed for a given zoom, matching the existing single-page clamp rule. */
internal fun maxCrossAxisPan(viewportExtent: Float, zoom: Float): Float =
    ((viewportExtent * zoom - viewportExtent) / 2f).coerceAtLeast(0f)

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

@Composable
private fun PageBitmapLayers(
    baseBitmap: Bitmap?,
    cropBitmap: Bitmap?,
    cropFrac: UnitRect?,
    boxSize: IntSize,
    contentDescription: String
) {
    Box(Modifier.fillMaxSize()) {
        if (baseBitmap != null) {
            Image(
                bitmap = baseBitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
        // Sharp crop tile of just the on-screen slice, positioned at its true sub-rect within
        // this page's box so it lines up with the (softer, whole-page) base bitmap beneath it.
        if (cropBitmap != null && cropFrac != null && boxSize != IntSize.Zero) {
            val density = LocalDensity.current
            val leftPx = cropFrac.left * boxSize.width
            val topPx = cropFrac.top * boxSize.height
            val wPx = ((cropFrac.right - cropFrac.left) * boxSize.width).coerceAtLeast(1f)
            val hPx = ((cropFrac.bottom - cropFrac.top) * boxSize.height).coerceAtLeast(1f)
            with(density) {
                Image(
                    bitmap = cropBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .offset(x = leftPx.toDp(), y = topPx.toDp())
                        .size(width = wPx.toDp(), height = hPx.toDp()),
                    contentScale = ContentScale.FillBounds
                )
            }
        }
    }
}

@Composable
internal fun ContinuousReferencePdfPane(
    modifier: Modifier = Modifier,
    orientation: Orientation,
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
    // True only while THIS composable is driving an animateScrollToItem below (external nav
    // request — scrollbar drag, resume-to-page). Distinguishes "the list moved because we
    // told it to" from "the list moved because the user swiped/panned it" — the snapshotFlow
    // below must not echo the former back as a nav request, or every animation frame it passes
    // through re-fires onCenteredPageChange -> scrollToPage -> cancels-and-restarts itself
    // mid-flight (this was the scrollbar-drag-vs-actual-drop-page mismatch: the animation kept
    // self-interrupting toward whatever intermediate page it last reported, not the page
    // actually requested).
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    // Shared whole-stack zoom: one zoom/pan transform applied over the entire scrollable
    // content, not per page. Lets the user pinch in and keep scrolling/panning freely across
    // page boundaries, matching how Word/Samsung Notes continuous view behaves.
    var sharedZoom by remember(fileIdentitySeed, orientation) { mutableFloatStateOf(CONTINUOUS_MIN_ZOOM) }
    var sharedCrossPan by remember(fileIdentitySeed, orientation) { mutableFloatStateOf(0f) }
    var isInteracting by remember(fileIdentitySeed, orientation) { mutableStateOf(false) }
    var isFlinging by remember(fileIdentitySeed, orientation) { mutableStateOf(false) }
    var flingJob by remember(fileIdentitySeed, orientation) { mutableStateOf<Job?>(null) }
    val flingScope = rememberCoroutineScope()
    var paneSize by remember { mutableStateOf(IntSize.Zero) }
    var paneRootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val zoomedIn = sharedZoom > 1.02f

    // awaitPointerEvent()'s scope is a restricted suspend scope (@RestrictsSuspension) — it
    // cannot directly call an arbitrary suspend function like listState.scrollBy(). Hand deltas
    // off through a channel instead and drain them sequentially on a plain coroutine, same
    // pattern Compose's own scrollable() uses internally for this exact restriction.
    val scrollDeltaChannel = remember(listState) { Channel<Float>(Channel.UNLIMITED) }
    // Channel is only needed for live-drag deltas: awaitPointerEvent runs in a
    // @RestrictsSuspension scope that cannot call arbitrary suspend fns like scrollBy().
    // Fling deltas are NOT sent through here — flingJob calls listState.scroll() directly.
    // A NaN sentinel causes the consumer to exit its current scroll session so the fling
    // can immediately acquire the scroll mutex without blocking.
    LaunchedEffect(scrollDeltaChannel, listState) {
        while (isActive) {
            val firstDelta = scrollDeltaChannel.receive()
            if (firstDelta.isNaN()) continue  // sentinel: fling is taking over, skip
            Log.d("PdfFlingDebug", "dragScrollSession START with firstDelta=$firstDelta")
            var count = 1
            listState.scroll(MutatePriority.UserInput) {
                scrollBy(firstDelta)
                while (isActive) {
                    val nextDelta = withTimeoutOrNull(32) {
                        scrollDeltaChannel.receive()
                    }
                    if (nextDelta != null && !nextDelta.isNaN()) {
                        scrollBy(nextDelta)
                        count++
                    } else {
                        // null = timeout (drag paused), NaN = fling sentinel: exit session
                        break
                    }
                }
            }
            Log.d("PdfFlingDebug", "dragScrollSession END processed deltas=$count")
        }
    }

    var lastReportedPage by remember(fileIdentitySeed) { mutableIntStateOf(scrollToPage) }

    // Rendering (in particular the expensive crop-tile re-render while zoomed) only kicks in
    // once input has settled — same debounce-after-settle rule the single-page viewer already
    // uses for its zoomed detail tile, so a fast pinch/scroll doesn't flood the render engine.
    var settled by remember(fileIdentitySeed, orientation) { mutableStateOf(true) }
    LaunchedEffect(isInteracting, listState.isScrollInProgress, isFlinging, fileIdentitySeed, orientation) {
        if (isInteracting || listState.isScrollInProgress || isFlinging) {
            settled = false
        } else {
            delay(120)
            settled = true
        }
    }

    LaunchedEffect(scrollToPage, totalPages) {
        Log.d("PdfFlingDebug", "LaunchedEffect(scrollToPage) triggered scrollToPage=$scrollToPage lastReported=$lastReportedPage isInteracting=$isInteracting isFlinging=$isFlinging")
        // Only jump for an EXTERNAL request (initial open, scrollbar drag, resume-to-page).
        if (totalPages <= 0 || scrollToPage !in 1..totalPages) return@LaunchedEffect
        if (scrollToPage == lastReportedPage || isInteracting) {
            Log.d("PdfFlingDebug", "LaunchedEffect(scrollToPage) SUPPRESSED: scrollToPage=$scrollToPage")
            lastReportedPage = scrollToPage
            return@LaunchedEffect
        }
        val current = listState.firstVisibleItemIndex + 1
        if (current == scrollToPage) {
            Log.d("PdfFlingDebug", "LaunchedEffect(scrollToPage) SUPPRESSED (already at current=$current)")
            lastReportedPage = scrollToPage
            return@LaunchedEffect
        }
        // Scrollbar (or any external nav) while flinging: kill the fling and jump.
        if (isFlinging) {
            Log.d("PdfFlingDebug", "LaunchedEffect(scrollToPage) INTERRUPTING fling for scrollToPage=$scrollToPage")
            flingJob?.cancel()
            isFlinging = false
        }
        Log.d("PdfFlingDebug", "LaunchedEffect(scrollToPage) EXECUTING animateScrollToItem target=${scrollToPage - 1}")
        isProgrammaticScroll = true
        try {
            lastReportedPage = scrollToPage
            listState.animateScrollToItem((scrollToPage - 1).coerceIn(0, totalPages - 1))
        } finally {
            isProgrammaticScroll = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) null else visible.first().index + 1
        }
            .distinctUntilChanged()
            .collectLatest { centeredPage ->
                Log.d("PdfFlingDebug", "snapshotFlow centeredPage=$centeredPage isProg=$isProgrammaticScroll")
                if (centeredPage != null && !isProgrammaticScroll) {
                    lastReportedPage = centeredPage
                    currentOnCenteredPageChange(centeredPage)
                }
            }
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
        var baseBitmap by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf<Bitmap?>(null) }
        var cropBitmap by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf<Bitmap?>(null) }
        var cropFrac by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf<UnitRect?>(null) }
        var aspectRatio by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf<Float?>(null) }
        // Real box size, needed so PdfMarkupOverlay's page transform is valid — with
        // viewSize left at IntSize.Zero, currentTransform() returns null and the overlay's
        // pointerInteropFilter bails out before ever registering a stroke. Also drives crop
        // tile placement (below) and the fallback render size before first layout.
        var boxSize by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf(IntSize.Zero) }
        var pageCoordinates by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf<LayoutCoordinates?>(null) }
        val matteColorArgb = if (preferDarkMode) MaterialTheme.colorScheme.surface.toArgb() else android.graphics.Color.WHITE

        LaunchedEffect(displayPage, resolved, file, fileIdentitySeed) {
            if (file == null) return@LaunchedEffect
            aspectRatio = withContext(Dispatchers.IO) {
                engineCache.get(file).pageAspectRatio((resolved.sourcePage - 1).coerceAtLeast(0))
            }
        }

        // Base (fit-to-box) bitmap: loaded once per page while in window, cheap + cached.
        // Doubles as the fallback shown under the sharp crop tile and as the whole picture
        // when not zoomed.
        LaunchedEffect(displayPage, resolved, file, inWindow, matteColorArgb, fileIdentitySeed) {
            if (file == null || !inWindow || baseBitmap != null) return@LaunchedEffect
            val viewSize = boxSize.takeIf { it != IntSize.Zero } ?: IntSize(1080, 1400)
            baseBitmap = withContext(Dispatchers.IO) {
                engineCache.get(file).renderBasePage(
                    pageIndex = (resolved.sourcePage - 1).coerceAtLeast(0),
                    viewSize = viewSize,
                    matteColorArgb = matteColorArgb
                )
            }
        }

        // Sharp crop of just the on-screen slice of this page — only while zoomed in and only
        // once the gesture/scroll has settled, so scrolling fast past a page never queues up a
        // render that immediately gets thrown away.
        LaunchedEffect(displayPage, resolved, file, inWindow, zoomedIn, settled, matteColorArgb, fileIdentitySeed) {
            if (!zoomedIn) {
                cropBitmap = null
                cropFrac = null
            }
            if (file == null || !inWindow || !zoomedIn || !settled) return@LaunchedEffect
            val pc = pageCoordinates
            val root = paneRootCoordinates
            if (pc == null || root == null || !pc.isAttached || paneSize == IntSize.Zero) return@LaunchedEffect
            val rect = root.localBoundingBoxOf(pc, clipBounds = false)
            val frac = visiblePageFraction(
                pageLeft = rect.left,
                pageTop = rect.top,
                pageRight = rect.right,
                pageBottom = rect.bottom,
                viewportWidth = paneSize.width.toFloat(),
                viewportHeight = paneSize.height.toFloat()
            ) ?: return@LaunchedEffect
            val outW = (rect.right - rect.left).roundToInt().coerceIn(1, 2200)
            val outH = (rect.bottom - rect.top).roundToInt().coerceIn(1, 2200)
            val tile = withContext(Dispatchers.IO) {
                engineCache.get(file).renderCropFraction(
                    pageIndex = (resolved.sourcePage - 1).coerceAtLeast(0),
                    cropLeft = frac.left,
                    cropTop = frac.top,
                    cropRight = frac.right,
                    cropBottom = frac.bottom,
                    outputSize = IntSize(outW, outH),
                    matteColorArgb = matteColorArgb
                )
            }
            if (tile != null) {
                cropBitmap = tile
                cropFrac = frac
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
                .onGloballyPositioned { pageCoordinates = it }
        ) {
            PageBitmapLayers(
                baseBitmap = baseBitmap,
                cropBitmap = cropBitmap,
                cropFrac = cropFrac,
                boxSize = boxSize,
                contentDescription = "Page $displayPage"
            )
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

    // Markup drawing pins the list to the current page — the pen and the scroll/zoom gesture
    // must not fight each other, same rule ReferencePdfPane applies today. When markup is
    // enabled our own gesture handler below is simply absent, so touches pass straight through
    // to PdfMarkupOverlay. The list's own userScrollEnabled stays false at all times regardless,
    // because scrolling is otherwise always driven programmatically by our handler rather than
    // the list's built-in touch handling — that built-in handling is what used to race against
    // each page's own independent pinch detector.
    val gesturesEnabled = !markupEnabled

    Box(
        modifier = modifier
            .onSizeChanged { paneSize = it }
            .onGloballyPositioned { paneRootCoordinates = it }
            .then(
                if (gesturesEnabled) {
                    // Single gesture owner for scroll AND pinch-zoom AND pan-while-zoomed.
                    // The previous design let each page's own pinch detector compete with the
                    // LazyColumn/LazyRow's built-in scrollable — a real pinch almost always
                    // starts with one finger moving slightly before the second lands, which was
                    // enough for the list to claim that pointer for scrolling before the second
                    // finger ever arrived, breaking pinch-to-zoom. Owning everything here (the
                    // list's userScrollEnabled is always false; see below) removes that race
                    // entirely, the same way the single-page viewer avoids it by not being
                    // nested inside a scrollable at all.
                    Modifier.pointerInput(orientation) {
                        awaitEachGesture {
                            // Do NOT cancel flingJob here — the coroutine hasn't executed yet
                            // (launch() schedules it; it won't run until this frame yields).
                            // Cancelling here kills the fling before it processes a single frame.
                            // Instead, the drag consumer's listState.scroll(UserInput) will
                            // naturally pre-empt the fling's scroll session when the new drag
                            // starts, and flingJob will be cancelled via Job cancellation at that
                            // point.
                            val velocityTracker = VelocityTracker()
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            val trackPointerId = firstDown.id  // only track this pointer — ignore second finger during pinch
                            velocityTracker.addPosition(firstDown.uptimeMillis, firstDown.position)
                            flingJob?.cancel()  // NOW cancel: we have a new real touch, pre-empt cleanly
                            isInteracting = true
                            var wasMultiTouch = false
                            do {
                                val event = awaitPointerEvent()
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (event.changes.size > 1) wasMultiTouch = true
                                // Only feed the original pointer into the velocity tracker.
                                // Adding multiple pointer positions produces garbage velocity on pinch release.
                                event.changes.firstOrNull { it.id == trackPointerId && it.pressed }
                                    ?.let { velocityTracker.addPosition(it.uptimeMillis, it.position) }
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val viewW = paneSize.width
                                val viewH = paneSize.height
                                val next = computeZoomPan(
                                    zoom = sharedZoom,
                                    panX = if (orientation == Orientation.Vertical) sharedCrossPan else 0f,
                                    panY = if (orientation == Orientation.Vertical) 0f else sharedCrossPan,
                                    zoomChange = zoomChange,
                                    panChange = panChange,
                                    centroid = centroid,
                                    viewWidth = viewW,
                                    viewHeight = viewH,
                                    minZoom = CONTINUOUS_MIN_ZOOM,
                                    maxZoom = CONTINUOUS_MAX_ZOOM
                                )
                                sharedZoom = next.zoom
                                when (orientation) {
                                    Orientation.Vertical -> {
                                        val maxCross = maxCrossAxisPan(viewW.toFloat(), next.zoom)
                                        sharedCrossPan = next.panX.coerceIn(-maxCross, maxCross)
                                        if (viewH > 0 && next.panY != 0f) {
                                            scrollDeltaChannel.trySend(-next.panY / next.zoom)
                                        }
                                    }
                                    Orientation.Horizontal -> {
                                        val maxCross = maxCrossAxisPan(viewH.toFloat(), next.zoom)
                                        sharedCrossPan = next.panY.coerceIn(-maxCross, maxCross)
                                        if (viewW > 0 && next.panX != 0f) {
                                            scrollDeltaChannel.trySend(-next.panX / next.zoom)
                                        }
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            isInteracting = false

                            val trackedVelocity = velocityTracker.calculateVelocity()
                            val rawMainVelocity = when (orientation) {
                                Orientation.Vertical -> -trackedVelocity.y
                                Orientation.Horizontal -> -trackedVelocity.x
                            }
                            val rawCrossVelocity = when (orientation) {
                                Orientation.Vertical -> trackedVelocity.x
                                Orientation.Horizontal -> trackedVelocity.y
                            }
                            val flingVelocity = if (wasMultiTouch) 0f else rawMainVelocity / sharedZoom
                            val crossFlingVelocity = if (wasMultiTouch) 0f else rawCrossVelocity / sharedZoom
                            Log.d("PdfFlingDebug", "Gesture end: tracked=$trackedVelocity main=$rawMainVelocity flingVel=$flingVelocity crossFlingVel=$crossFlingVelocity zoom=$sharedZoom multiTouch=$wasMultiTouch")

                            if (abs(flingVelocity) > 100f || abs(crossFlingVelocity) > 100f) {
                                Log.d("PdfFlingDebug", "Launching flingJob with vel=$flingVelocity crossVel=$crossFlingVelocity")
                                // Drain all real drag deltas, then post a NaN sentinel to force
                                // the drag consumer to exit its listState.scroll() session.
                                // This clears the scroll mutex before flingJob tries to acquire it.
                                while (scrollDeltaChannel.tryReceive().isSuccess) { /* drain */ }
                                scrollDeltaChannel.trySend(Float.NaN)  // sentinel: release drag session
                                flingJob = flingScope.launch {
                                    Log.d("PdfFlingDebug", "flingJob ENTERED coroutine body vel=$flingVelocity")
                                    isFlinging = true
                                    try {
                                        var vel = flingVelocity
                                        var crossVel = crossFlingVelocity
                                        // Drive the entire fling inside ONE continuous scroll
                                        // session so LazyList never thinks scrolling stopped
                                        // mid-flight. Using MutatePriority.UserInput so a
                                        // subsequent touch (flingJob?.cancel() in awaitFirstDown)
                                        // can pre-empt it cleanly.
                                        listState.scroll(MutatePriority.UserInput) {
                                            var lastFrameNanos = System.nanoTime()
                                            var frameCount = 0
                                            while (isActive && abs(vel) > 10f) {
                                                withFrameNanos { frameTime ->
                                                    val dt = ((frameTime - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.05f)
                                                    lastFrameNanos = frameTime

                                                    // Zoomed-in fling decays ~2x faster (0.75s glide vs 1.5s at
                                                    // rest zoom) — doubling friction exactly halves decay time
                                                    // since t = ln(v0/threshold)/friction.
                                                    val effectiveFriction = if (sharedZoom > 1.02f) 5f else 2.5f
                                                    val mainStep = computeFlingStep(vel, dt, friction = effectiveFriction)
                                                    vel = mainStep.nextVelocity
                                                    if (mainStep.delta != 0f) {
                                                        scrollBy(mainStep.delta)
                                                        frameCount++
                                                    }

                                                    if (sharedZoom > 1.02f && abs(crossVel) > 10f) {
                                                        val crossStep = computeFlingStep(crossVel, dt, friction = effectiveFriction)
                                                        crossVel = crossStep.nextVelocity
                                                        if (crossStep.delta != 0f) {
                                                            val viewExtent = if (orientation == Orientation.Vertical) paneSize.width else paneSize.height
                                                            val maxCross = maxCrossAxisPan(viewExtent.toFloat(), sharedZoom)
                                                            sharedCrossPan = (sharedCrossPan + crossStep.delta).coerceIn(-maxCross, maxCross)
                                                        }
                                                    } else {
                                                        crossVel = 0f
                                                    }
                                                }
                                            }
                                            Log.d("PdfFlingDebug", "flingJob scrollSession finished frames=$frameCount remainingVel=$vel")
                                        }
                                        Log.d("PdfFlingDebug", "flingJob finished loop cleanly")
                                    } finally {
                                        isFlinging = false
                                        Log.d("PdfFlingDebug", "flingJob finally block executed")
                                    }
                                }
                            } else {
                                Log.d("PdfFlingDebug", "Fling threshold NOT met (vel=$flingVelocity <= 100)")
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = sharedZoom
                    scaleY = sharedZoom
                    when (orientation) {
                        Orientation.Vertical -> translationX = sharedCrossPan
                        Orientation.Horizontal -> translationY = sharedCrossPan
                    }
                }
        ) {
            if (orientation == Orientation.Vertical) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    userScrollEnabled = false,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(count = totalPages, key = { it + 1 }) { index -> pageContent(index + 1) }
                }
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    userScrollEnabled = false,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(count = totalPages, key = { it + 1 }) { index ->
                        Box(Modifier.fillParentMaxWidth()) { pageContent(index + 1) }
                    }
                }
            }
        }
    }
}
