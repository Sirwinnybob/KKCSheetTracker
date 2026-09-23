package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.cancel
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

private const val CONTINUOUS_MAX_ZOOM = 20f
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

/** Bounds of a sharp crop in the padded content host's coordinate space. */
internal data class ContinuousCropOverlayBounds(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float
)

/**
 * Maps a previously rendered PDF-relative crop back through the page's current transformed
 * bounds. The caller clips the resulting full-size rectangle; it must never edge-resize it.
 */
internal fun resolveContinuousCropOverlayBounds(
    pageLeftPx: Float,
    pageTopPx: Float,
    pageRightPx: Float,
    pageBottomPx: Float,
    cropFrac: UnitRect
): ContinuousCropOverlayBounds {
    val pageWidth = pageRightPx - pageLeftPx
    val pageHeight = pageBottomPx - pageTopPx
    return ContinuousCropOverlayBounds(
        leftPx = pageLeftPx + cropFrac.left * pageWidth,
        topPx = pageTopPx + cropFrac.top * pageHeight,
        widthPx = (cropFrac.right - cropFrac.left) * pageWidth,
        heightPx = (cropFrac.bottom - cropFrac.top) * pageHeight
    )
}

internal fun continuousPdfCanvasColor(
    preferDarkMode: Boolean,
    lightCanvasColor: Color
): Color = if (preferDarkMode) Color.Black else lightCanvasColor

internal fun continuousPdfMatteColorArgb(
    preferDarkMode: Boolean,
    lightMatteColorArgb: Int
): Int = if (preferDarkMode) android.graphics.Color.BLACK else lightMatteColorArgb

/**
 * Chooses the page represented by continuous scrolling. The usual first-visible rule preserves
 * the existing upward behavior, but once the real LazyList reaches its end the final visible page
 * must own the scrollbar marker instead of leaving it stranded on a preceding page.
 */
internal fun continuousCurrentPage(
    firstVisibleIndex: Int?,
    lastVisibleIndex: Int?,
    canScrollForward: Boolean
): Int? {
    val first = firstVisibleIndex ?: return null
    return if (!canScrollForward && first > 0) (lastVisibleIndex ?: first) + 1 else first + 1
}

internal fun continuousMainAxisScrollDelta(
    panDelta: Float,
    zoom: Float,
    viewportExtent: Int
): Float? = if (viewportExtent <= 0 || panDelta == 0f) {
    null
} else {
    -panDelta / zoom
}

/**
 * Whether the pane's scroll/pinch/tap handler should run for finger input. With ink on it stays
 * on so fingers keep scrolling and zooming while the stylus draws or erases — except when finger
 * drawing is enabled, where a finger marks the page and the list must lock so it doesn't scroll
 * and mark at once. The eraser tool erases with the pen only, so it doesn't lock the list.
 */
internal fun shouldContinuousPaneOwnFingerGestures(
    markupEnabled: Boolean,
    allowFingerDrawing: Boolean
): Boolean = !markupEnabled || !allowFingerDrawing

/** Pen and pen-eraser pointers belong to the markup overlay, never to scroll/zoom. */
internal fun isStylusPointerType(type: PointerType): Boolean =
    type == PointerType.Stylus || type == PointerType.Eraser

internal fun hasContinuousPdfPageRenderDwelled(
    visibleSinceMillis: Long?,
    nowMillis: Long
): Boolean = visibleSinceMillis != null && nowMillis - visibleSinceMillis >= 300L

/**
 * A light/dark source swap invalidates the only sharp bitmap while the page remains zoomed.
 * That replacement must not wait for a possibly stale scroll-settle signal; ordinary gesture
 * and scroll updates still retain the debounce.
 */
internal fun shouldRenderContinuousPdfCrop(
    inWindow: Boolean,
    zoomedIn: Boolean,
    settled: Boolean,
    sourceVariantChanged: Boolean,
    sourceGeometryReady: Boolean = true
): Boolean = sourceGeometryReady && inWindow && zoomedIn && (settled || sourceVariantChanged)

internal fun coalesceMainAxisDelta(pending: Float, incoming: Float): Float = pending + incoming

/**
 * Delivers drag deltas through one bounded pending slot. Producers coalesce into that slot so no
 * movement is dropped, while a conflated wake-up prevents a high-frequency pointer stream from
 * building an unbounded queue behind the LazyList consumer.
 */
internal class CoalescingMainAxisDeltaChannel {
    private val stateLock = Any()
    private var pendingBeforeHandoff = 0f
    private var pendingAfterHandoff = 0f
    private var handoffRequested = false
    private var handoffDelivered = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun trySend(delta: Float) {
        synchronized(stateLock) {
            if (delta.isNaN()) {
                handoffRequested = true
            } else if (delta.isFinite() && delta != 0f) {
                if (handoffRequested || handoffDelivered || pendingAfterHandoff != 0f) {
                    pendingAfterHandoff = coalesceMainAxisDelta(pendingAfterHandoff, delta)
                } else {
                    pendingBeforeHandoff = coalesceMainAxisDelta(pendingBeforeHandoff, delta)
                }
            }
        }
        wake.trySend(Unit)
    }

    suspend fun receive(): Float {
        while (true) {
            if (wake.receiveCatching().isClosed) return Float.NaN
            val delivery = synchronized(stateLock) {
                when {
                    pendingBeforeHandoff != 0f -> {
                        val delta = pendingBeforeHandoff
                        pendingBeforeHandoff = 0f
                        delta to (handoffRequested || pendingAfterHandoff != 0f)
                    }
                    handoffRequested -> {
                        handoffRequested = false
                        handoffDelivered = true
                        Float.NaN to (pendingAfterHandoff != 0f)
                    }
                    pendingAfterHandoff != 0f -> {
                        val delta = pendingAfterHandoff
                        pendingAfterHandoff = 0f
                        handoffDelivered = false
                        delta to false
                    }
                    else -> null
                }
            }
            if (delivery != null) {
                if (delivery.second) wake.trySend(Unit)
                return delivery.first
            }
        }
    }
}

/**
 * Tracks whether a programmatic (list-driven) scroll is in flight, immune to the out-of-order
 * cleanup that happens when a new `scrollToPage` value cancels a still-running
 * `animateScrollToItem` before the request that superseded it has claimed the guard.
 *
 * A plain boolean is not enough here: when `scrollToPage` changes, Compose cancels the previous
 * `LaunchedEffect(scrollToPage, totalPages)` invocation and launches a new one, but the old
 * invocation's `finally` block and the new invocation's first line are two separate coroutines
 * racing on the same dispatcher -- their relative order is not guaranteed. If the old `finally`
 * clears the guard AFTER the new invocation has already started, the centered-page listener
 * observes the list's transient, uncontrolled position (wherever the cancelled animation was
 * mid-flight) as if it were a fresh external nav request, feeding it back as a new `scrollToPage`
 * and ratcheting the list further away from any position either side actually asked for -- this
 * is the mechanism behind sheets rendering out of order after scrolling around for a while.
 *
 * Each call to [begin] claims a new generation; [release] only clears the guard if no newer
 * generation has since claimed it, so a stale/cancelled request's cleanup can never release a
 * guard that a newer, still-in-flight request is holding.
 */
internal class ProgrammaticScrollGuard {
    private var activeGeneration = 0
    private var settledGeneration = 0

    val isActive: Boolean get() = activeGeneration != settledGeneration

    fun begin(): Int = ++activeGeneration

    fun release(token: Int) {
        settledGeneration = token
    }
}

/**
 * Identity for the source and render variant currently backing one continuous-mode page.
 *
 * The resolved filename and page can stay the same while [preferDarkMode] switches the backing
 * file (for example, to the corresponding file under DARK MODE). Keeping the variant in this
 * identity lets Compose discard page render state instead of retaining the previous bitmap.
 */
internal data class ContinuousPageRenderIdentity(
    val displayPage: Int,
    val resolved: ResolvedPageSource,
    val filePath: String?,
    val preferDarkMode: Boolean
)

internal fun continuousPageRenderIdentity(
    displayPage: Int,
    resolved: ResolvedPageSource,
    file: java.io.File?,
    preferDarkMode: Boolean
): ContinuousPageRenderIdentity = ContinuousPageRenderIdentity(
    displayPage = displayPage,
    resolved = resolved,
    filePath = file?.absolutePath,
    preferDarkMode = preferDarkMode
)

/**
 * Identity for layout geometry that should survive a source-variant swap. The page's measured
 * bounds and coordinates remain valid while light/dark render bitmaps are being replaced.
 */
internal data class ContinuousPageGeometryIdentity(
    val displayPage: Int,
    val resolved: ResolvedPageSource,
    val documentIdentity: ContinuousPdfDocumentIdentity,
    val docKey: Any?
)

internal fun continuousPageGeometryIdentity(
    renderIdentity: ContinuousPageRenderIdentity,
    documentIdentity: ContinuousPdfDocumentIdentity,
    docKey: Any?
): ContinuousPageGeometryIdentity = ContinuousPageGeometryIdentity(
    displayPage = renderIdentity.displayPage,
    resolved = renderIdentity.resolved,
    documentIdentity = documentIdentity,
    docKey = docKey
)

private const val CONTINUOUS_MAX_CROP_PIXELS = 8_000_000L

/**
 * Keeps crop tiles pixel-matched to their on-screen bounds when they fit a sane memory budget.
 * Oversized requests are skipped rather than downscaled, because [PageBitmapLayers] stretches a
 * crop tile to those same bounds and downscaling would leave a visibly blurry border.
 */
internal fun resolveCropRenderSize(
    requestedSize: IntSize,
    maxPixels: Long = CONTINUOUS_MAX_CROP_PIXELS
): IntSize? {
    val width = requestedSize.width.coerceAtLeast(1)
    val height = requestedSize.height.coerceAtLeast(1)
    if (maxPixels <= 0L || width.toLong() > maxPixels / height.toLong()) return null
    return IntSize(width, height)
}

/**
 * Resolves the output size for the visible slice of a transformed page. The full page can be far
 * larger than the viewport at high zoom; only the fraction actually shown on screen belongs in
 * the crop tile's pixel budget.
 */
internal fun resolveVisibleCropRenderSize(
    pageWidthPx: Float,
    pageHeightPx: Float,
    cropFrac: UnitRect,
    maxPixels: Long = CONTINUOUS_MAX_CROP_PIXELS
): IntSize? = resolveCropRenderSize(
    requestedSize = IntSize(
        width = ((cropFrac.right - cropFrac.left).coerceAtLeast(0f) * pageWidthPx).roundToInt(),
        height = ((cropFrac.bottom - cropFrac.top).coerceAtLeast(0f) * pageHeightPx).roundToInt()
    ),
    maxPixels = maxPixels
)

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
 * Max [sharedMainAxisOverscroll]-style main-axis overscroll (list-space px, not screen px)
 * needed at the start OR end of the scrollable content so the first/last page can be dragged
 * half a viewport past its far edge.
 *
 * The shared whole-stack zoom is a `graphicsLayer` scale pivoted at the center of the viewport
 * (Compose's default transformOrigin), applied on top of the LazyColumn/LazyRow's own unscaled
 * layout. The matching list-space allowance is half the unscaled viewport. After the layer scale
 * is applied, it places the first/last page edge half of the visible viewport beyond the pane,
 * regardless of zoom.
 */
internal fun mainAxisEdgePadding(viewportExtent: Float, zoom: Float): Float =
    viewportExtent / 2f

internal data class ScrollSplitResult(val realScrollDelta: Float, val overscrollDelta: Float)

/**
 * Splits a requested main-axis scroll delta (list-space units, forward-positive — the same
 * convention as [androidx.compose.foundation.gestures.ScrollScope.scrollBy]) between the real
 * LazyColumn/LazyRow scroll and [sharedMainAxisOverscroll], the clamped translation that reveals
 * the start/end boundary overflow described on [mainAxisEdgePadding].
 *
 * Draining (a delta pulling back toward real content) always empties existing overscroll first,
 * so the handoff between "scrolling within the document" and "revealing the boundary overflow"
 * is seamless in both directions — the caller applies [ScrollSplitResult.realScrollDelta] via a
 * real `scrollBy` call and adds [ScrollSplitResult.overscrollDelta] to the running overscroll
 * total (see call sites for how a real-scroll shortfall folds in too).
 */
internal fun splitScrollDelta(
    requestedDelta: Float,
    currentOverscroll: Float,
    maxOverscroll: Float
): ScrollSplitResult = when {
    currentOverscroll > 0f && requestedDelta >= 0f -> {
        val newOverscroll = (currentOverscroll + requestedDelta).coerceAtMost(maxOverscroll)
        ScrollSplitResult(realScrollDelta = 0f, overscrollDelta = newOverscroll - currentOverscroll)
    }
    currentOverscroll > 0f -> { // requestedDelta < 0: draining back toward real content
        val drain = min(-requestedDelta, currentOverscroll)
        ScrollSplitResult(realScrollDelta = requestedDelta + drain, overscrollDelta = -drain)
    }
    currentOverscroll < 0f && requestedDelta <= 0f -> {
        val newOverscroll = (currentOverscroll + requestedDelta).coerceAtLeast(-maxOverscroll)
        ScrollSplitResult(realScrollDelta = 0f, overscrollDelta = newOverscroll - currentOverscroll)
    }
    currentOverscroll < 0f -> { // requestedDelta > 0: draining back toward real content
        val drain = min(requestedDelta, -currentOverscroll)
        ScrollSplitResult(realScrollDelta = requestedDelta - drain, overscrollDelta = drain)
    }
    else -> ScrollSplitResult(realScrollDelta = requestedDelta, overscrollDelta = 0f)
}

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

internal fun continuousPdfDocumentFlingScope(parentScope: CoroutineScope): CoroutineScope =
    CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])
    )

private data class ContinuousCropOverlay(
    val renderIdentity: ContinuousPageRenderIdentity,
    val bitmap: Bitmap,
    val cropFrac: UnitRect
)

@Composable
private fun PageBitmapLayers(
    baseBitmap: Bitmap?,
    thumbnailBitmap: Bitmap?,
    emptyCanvasColor: Color,
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
        } else if (thumbnailBitmap != null) {
            Image(
                bitmap = thumbnailBitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(Modifier.fillMaxSize().background(emptyCanvasColor))
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
    // Identifies which document set resolvePage/pdfFileForFilename currently resolve against
    // (e.g. defaultPdfFilename + virtualMapping). resolvePage is a fresh lambda instance every
    // recomposition, so it can't be used as a remember key by itself without recomputing on
    // every frame — but without ANY key covering a doc-type switch, an already-composed
    // LazyColumn item (still inside the render window) keeps resolving through its stale
    // closure, showing the previous document's pages until that item leaves and re-enters the
    // composition window (i.e. until the user scrolls away and back).
    docKey: Any? = null,
    preferDarkMode: Boolean = false,
    onCenteredPageChange: (Int) -> Unit,
    scrollToPage: Int,
    markupEnabled: Boolean = false,
    markupToolState: PdfMarkupToolState? = null,
    markupStrokesForPage: (sourceFilename: String, sourcePage: Int) -> List<PdfInkStroke> = { _, _ -> emptyList() },
    onMarkupStrokeAdded: ((sourceFilename: String, sourcePage: Int, PdfInkStroke) -> Unit)? = null,
    onMarkupStrokeErased: ((sourceFilename: String, sourcePage: Int, strokeId: String) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onSingleTap: (() -> Unit)? = null,
    onEdgeOverscrollChange: (Float) -> Unit = {}
) {
    val documentIdentity = remember(fileIdentitySeed, totalPages, docKey, preferDarkMode) {
        resolveContinuousPdfDocumentIdentity(totalPages, resolvePage, pdfFileForFilename)
    }
    val engineCache = remember(documentIdentity) { PdfEngineCache(maxOpen = 3) }
    DisposableEffect(engineCache) {
        onDispose { continuousPdfEngineDisposalScope.launch { withContext(NonCancellable) { engineCache.closeAll() } } }
    }

    // Cheap low-res placeholder cache, keyed by "filename#page" (a plain Int page number isn't
    // enough — this pane can stream pages from multiple source PDFs). Shown immediately on page
    // entry while the full-res base render waits for a page-local visibility dwell.
    val thumbnailCache = remember(documentIdentity) { LruCache<String, Bitmap>(30) }
    DisposableEffect(thumbnailCache) {
        onDispose { thumbnailCache.evictAll() }
    }

    val listState = rememberLazyListState()
    val cropOverlays = remember(documentIdentity, docKey, preferDarkMode) {
        mutableStateMapOf<Int, ContinuousCropOverlay>()
    }
    val pageCoordinatesByDisplayPage = remember(documentIdentity, docKey) {
        mutableStateMapOf<Int, LayoutCoordinates>()
    }
    // Pages whose ink overlay should exist, registered by pageContent. The overlays themselves
    // are composed in the outer (unscaled) layer, above the sharp crop canvas — see
    // ContinuousInkOverlayLayer.
    val inkPagesByDisplayPage = remember(documentIdentity, docKey) {
        mutableStateMapOf<Int, ContinuousInkPage>()
    }
    val currentOnCenteredPageChange by rememberUpdatedState(onCenteredPageChange)
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)
    val currentOnEdgeOverscrollChange by rememberUpdatedState(onEdgeOverscrollChange)
    val currentMarkupEnabled by rememberUpdatedState(markupEnabled)
    val touchSlop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
    // True only while THIS composable is driving an animateScrollToItem below (external nav
    // request — scrollbar drag, resume-to-page). Distinguishes "the list moved because we
    // told it to" from "the list moved because the user swiped/panned it" — the snapshotFlow
    // below must not echo the former back as a nav request, or every animation frame it passes
    // through re-fires onCenteredPageChange -> scrollToPage -> cancels-and-restarts itself
    // mid-flight (this was the scrollbar-drag-vs-actual-drop-page mismatch: the animation kept
    // self-interrupting toward whatever intermediate page it last reported, not the page
    // actually requested). A plain boolean is not safe here -- see ProgrammaticScrollGuard's doc
    // comment for why a new scrollToPage's request can race the previous one's cleanup.
    val programmaticScrollGuard = remember(documentIdentity) { ProgrammaticScrollGuard() }

    // Shared whole-stack zoom: one zoom/pan transform applied over the entire scrollable
    // content, not per page. Lets the user pinch in and keep scrolling/panning freely across
    // page boundaries, matching how Word/Samsung Notes continuous view behaves.
    var sharedZoom by remember(documentIdentity, orientation) { mutableFloatStateOf(CONTINUOUS_MIN_ZOOM) }
    var sharedCrossPan by remember(documentIdentity, orientation) { mutableFloatStateOf(0f) }
    // Extra reveal at the very start/end of the document — the portion of page 1's top edge
    // (or the last page's bottom edge) that the center-pivoted graphicsLayer scale above
    // pushes off-screen, with no real LazyColumn content left to scroll into to reach it.
    // List-space (unscaled) units, same convention as ScrollScope.scrollBy's delta: positive
    // means overscrolled past the END boundary, negative past the START boundary. Handled as
    // a plain clamped translation (like sharedCrossPan) rather than LazyColumn contentPadding
    // specifically because contentPadding changes trigger a real relayout that visibly snaps
    // page position when the value changes while pinned at a boundary — fine for cross-axis
    // pan (nothing else touches viewport translation) but wrong here, where real list scroll
    // is also fighting for the same axis. See splitScrollDelta for how a raw drag/fling delta
    // is divided between real scroll and this overscroll.
    var sharedMainAxisOverscroll by remember(documentIdentity, orientation) { mutableFloatStateOf(0f) }
    var isInteracting by remember(documentIdentity, orientation) { mutableStateOf(false) }
    var isFlinging by remember(documentIdentity, orientation) { mutableStateOf(false) }
    var flingJob by remember(documentIdentity, orientation) { mutableStateOf<Job?>(null) }
    val flingScope = rememberCoroutineScope()
    val documentFlingScope = remember(documentIdentity, orientation) {
        continuousPdfDocumentFlingScope(flingScope)
    }
    DisposableEffect(documentFlingScope) {
        onDispose { documentFlingScope.cancel() }
    }
    var paneSize by remember { mutableStateOf(IntSize.Zero) }
    var paneRootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var contentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val zoomedIn = sharedZoom > 1.02f
    // Positions each page's ink overlay over that page's live on-screen bounds. Keys cover every
    // state object the lambdas capture (sharedZoom & co. are keyed by documentIdentity +
    // orientation, the coordinates map by documentIdentity + docKey), so they never read a stale
    // state instance.
    val inkOverlayPlacer = remember(documentIdentity, docKey, orientation, listState) {
        ContinuousInkOverlayPlacer(
            pageCoordinatesByDisplayPage = pageCoordinatesByDisplayPage,
            hostCoordinates = { contentCoordinates },
            motionFrame = {
                sharedZoom + sharedCrossPan + sharedMainAxisOverscroll +
                    listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset
            }
        )
    }

    val viewportExtent = if (orientation == Orientation.Vertical) paneSize.height.toFloat() else paneSize.width.toFloat()
    val maxEdgeOverscroll = mainAxisEdgePadding(viewportExtent, sharedZoom)
    val edgeOverscrollFraction = if (maxEdgeOverscroll > 0f) {
        (sharedMainAxisOverscroll / maxEdgeOverscroll).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    } else {
        0f
    }
    SideEffect { currentOnEdgeOverscrollChange(edgeOverscrollFraction) }
    DisposableEffect(documentIdentity, orientation) {
        onDispose { currentOnEdgeOverscrollChange(0f) }
    }

    LaunchedEffect(zoomedIn) {
        if (!zoomedIn) cropOverlays.clear()
    }

    // Applies a main-axis scroll delta (list-space units), splitting it between real list
    // scroll and sharedMainAxisOverscroll via splitScrollDelta. Must run inside a
    // listState.scroll { } block, same as a plain scrollBy call, since it calls scrollBy
    // itself for the realScrollDelta portion. Shared by the live-drag consumer and the fling
    // loop below so both paths hit the boundary the same way.
    val applyMainAxisScroll: ScrollScope.(Float) -> Unit = scroll@{ delta ->
        val viewportExtent = if (orientation == Orientation.Vertical) paneSize.height.toFloat() else paneSize.width.toFloat()
        val maxOverscroll = mainAxisEdgePadding(viewportExtent, sharedZoom)
        val split = splitScrollDelta(delta, sharedMainAxisOverscroll, maxOverscroll)
        val consumed = if (split.realScrollDelta != 0f) scrollBy(split.realScrollDelta) else 0f
        val shortfall = split.realScrollDelta - consumed
        sharedMainAxisOverscroll = (sharedMainAxisOverscroll + split.overscrollDelta + shortfall)
            .coerceIn(-maxOverscroll, maxOverscroll)
    }

    // awaitPointerEvent()'s scope is a restricted suspend scope (@RestrictsSuspension) — it
    // cannot directly call an arbitrary suspend function like listState.scrollBy(). Hand deltas
    // off through a channel instead and drain them sequentially on a plain coroutine, same
    // pattern Compose's own scrollable() uses internally for this exact restriction.
    val scrollDeltaChannel = remember(listState, documentIdentity) { CoalescingMainAxisDeltaChannel() }
    // Channel is only needed for live-drag deltas: awaitPointerEvent runs in a
    // @RestrictsSuspension scope that cannot call arbitrary suspend fns like scrollBy().
    // Fling deltas are NOT sent through here — flingJob calls listState.scroll() directly.
    // A NaN sentinel causes the consumer to exit its current scroll session so the fling
    // can immediately acquire the scroll mutex without blocking.
    LaunchedEffect(scrollDeltaChannel, listState) {
        while (isActive) {
            val firstDelta = scrollDeltaChannel.receive()
            if (firstDelta.isNaN()) continue  // sentinel: fling is taking over, skip
            listState.scroll(MutatePriority.UserInput) {
                applyMainAxisScroll(firstDelta)
                while (isActive) {
                    val nextDelta = withTimeoutOrNull(32) {
                        scrollDeltaChannel.receive()
                    }
                    if (nextDelta != null && !nextDelta.isNaN()) {
                        applyMainAxisScroll(nextDelta)
                    } else {
                        // null = timeout (drag paused), NaN = fling sentinel: exit session
                        break
                    }
                }
            }
        }
    }

    var lastReportedPage by remember(documentIdentity) { mutableIntStateOf(scrollToPage) }

    // Crop-tile rendering (in particular the expensive zoomed re-render) only kicks in once
    // input has settled — same debounce-after-settle rule the single-page viewer already uses
    // for its zoomed detail tile, so a fast pinch/scroll doesn't flood the render engine.
    var settled by remember(documentIdentity, orientation) { mutableStateOf(true) }
    LaunchedEffect(isInteracting, listState.isScrollInProgress, isFlinging, documentIdentity, orientation) {
        if (isInteracting || listState.isScrollInProgress || isFlinging) {
            settled = false
        } else {
            delay(120)
            settled = true
        }
    }

    LaunchedEffect(scrollToPage, totalPages) {
        // Only jump for an EXTERNAL request (initial open, scrollbar drag, resume-to-page).
        if (totalPages <= 0 || scrollToPage !in 1..totalPages) return@LaunchedEffect
        // A page selection is a new document position. It must not inherit the temporary visual
        // translation used to reveal the leading/trailing blank space at the previous boundary.
        sharedMainAxisOverscroll = 0f
        if (isInteracting) {
            lastReportedPage = scrollToPage
            return@LaunchedEffect
        }
        val current = listState.firstVisibleItemIndex + 1
        if (current == scrollToPage) {
            lastReportedPage = scrollToPage
            return@LaunchedEffect
        }
        // Scrollbar (or any external nav) while flinging: kill the fling and jump.
        if (isFlinging) {
            flingJob?.cancel()
            isFlinging = false
        }
        val scrollToken = programmaticScrollGuard.begin()
        try {
            lastReportedPage = scrollToPage
            listState.animateScrollToItem((scrollToPage - 1).coerceIn(0, totalPages - 1))
        } finally {
            programmaticScrollGuard.release(scrollToken)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            continuousCurrentPage(
                firstVisibleIndex = visible.firstOrNull()?.index,
                lastVisibleIndex = visible.lastOrNull()?.index,
                canScrollForward = listState.canScrollForward
            )
        }
            .distinctUntilChanged()
            .collectLatest { centeredPage ->
                if (centeredPage != null && !programmaticScrollGuard.isActive) {
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
    val visiblePages by remember(totalPages) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .map { it.index + 1 }
                .toSet()
        }
    }

    val pageContent: @Composable (Int) -> Unit = { displayPage ->
        val resolved = remember(displayPage, documentIdentity, docKey, preferDarkMode) { resolvePage(displayPage) }
        val file = remember(resolved.pdfFilename, documentIdentity, docKey, preferDarkMode) {
            pdfFileForFilename(resolved.pdfFilename)
        }
        val renderIdentity = continuousPageRenderIdentity(
            displayPage = displayPage,
            resolved = resolved,
            file = file,
            preferDarkMode = preferDarkMode
        )
        val inWindow = displayPage in renderWindow
        val pageIsVisible = displayPage in visiblePages
        var baseBitmap by remember(renderIdentity, documentIdentity, docKey) { mutableStateOf<Bitmap?>(null) }
        var thumbnailBitmap by remember(renderIdentity, documentIdentity, docKey) { mutableStateOf<Bitmap?>(null) }
        var aspectRatio by remember(renderIdentity, documentIdentity, docKey) { mutableStateOf<Float?>(null) }
        var sourceGeometryReady by remember(renderIdentity, documentIdentity, docKey) { mutableStateOf(false) }
        var baseRenderEligible by remember(renderIdentity, documentIdentity, docKey) { mutableStateOf(false) }
        var visibleSinceMillis by remember(renderIdentity, documentIdentity, docKey) { mutableStateOf<Long?>(null) }
        // Keep this across the render-identity swap: when the timeout toggles the light/dark
        // source while a page is zoomed, the new source needs one immediate sharp crop even when
        // the continuous list has not yet reported that it settled.
        var lastRenderedCropVariant by remember(displayPage, documentIdentity, docKey) {
            mutableStateOf(preferDarkMode)
        }
        val sourceVariantChanged = lastRenderedCropVariant != preferDarkMode
        // Real (unscaled) box size. The outer ink layer scales it by sharedZoom to size this
        // page's PdfMarkupOverlay — with it left at IntSize.Zero the overlay's page transform
        // is null and its pointerInteropFilter bails out before ever registering a stroke. Also
        // drives the fallback render size before first layout.
        val geometryIdentity = continuousPageGeometryIdentity(renderIdentity, documentIdentity, docKey)
        var boxSize by remember(geometryIdentity) { mutableStateOf(IntSize.Zero) }
        var pageCoordinates by remember(geometryIdentity) { mutableStateOf<LayoutCoordinates?>(null) }
        DisposableEffect(displayPage, documentIdentity) {
            onDispose {
                pageCoordinatesByDisplayPage.remove(displayPage)
                inkPagesByDisplayPage.remove(displayPage)
                cropOverlays.remove(displayPage)
            }
        }
        val matteColorArgb = continuousPdfMatteColorArgb(
            preferDarkMode = preferDarkMode,
            lightMatteColorArgb = android.graphics.Color.WHITE
        )
        val emptyCanvasColor = continuousPdfCanvasColor(
            preferDarkMode = preferDarkMode,
            lightCanvasColor = MaterialTheme.colorScheme.surfaceVariant
        )

        LaunchedEffect(renderIdentity, documentIdentity, docKey) {
            if (file == null) return@LaunchedEffect
            aspectRatio = withContext(Dispatchers.IO) {
                engineCache.get(file).pageAspectRatio((resolved.sourcePage - 1).coerceAtLeast(0))
            }
        }

        // A source swap (light <-> dark PDF) can change the page aspect ratio. Do not crop from
        // the outgoing page bounds; wait through the layout frame that applies the new ratio.
        LaunchedEffect(renderIdentity, aspectRatio) {
            if (aspectRatio == null) return@LaunchedEffect
            withFrameNanos { }
            sourceGeometryReady = true
        }

        LaunchedEffect(renderIdentity, pageIsVisible, documentIdentity, docKey) {
            if (!pageIsVisible) {
                visibleSinceMillis = null
                baseRenderEligible = false
                cropOverlays.remove(displayPage)
                return@LaunchedEffect
            }
            val dwellStartMillis = SystemClock.uptimeMillis()
            visibleSinceMillis = dwellStartMillis
            baseRenderEligible = false
            delay(300L)
            if (pageIsVisible && hasContinuousPdfPageRenderDwelled(
                    visibleSinceMillis = visibleSinceMillis,
                    nowMillis = SystemClock.uptimeMillis()
                )
            ) {
                baseRenderEligible = true
            }
        }

        // Base (fit-to-box) bitmap: loaded once per page while in window, cheap + cached.
        // Doubles as the fallback shown under the sharp crop tile and as the whole picture
        // when not zoomed. A page must remain actually visible for the full dwell before this
        // expensive decode starts; buffered pages still receive immediate thumbnails below.
        LaunchedEffect(renderIdentity, inWindow, pageIsVisible, baseRenderEligible, matteColorArgb, documentIdentity, docKey) {
            if (file == null || !inWindow || !pageIsVisible || !baseRenderEligible || baseBitmap != null) return@LaunchedEffect
            val viewSize = boxSize.takeIf { it != IntSize.Zero } ?: IntSize(1080, 1400)
            baseBitmap = withContext(Dispatchers.IO) {
                engineCache.get(file).renderBasePage(
                    pageIndex = (resolved.sourcePage - 1).coerceAtLeast(0),
                    viewSize = viewSize,
                    matteColorArgb = matteColorArgb
                )
            }
        }

        // Instant low-res placeholder: unconditional on settled (unlike the base render below),
        // so a page that's merely scrolled past during a fling still shows something recognizable.
        LaunchedEffect(renderIdentity, inWindow, documentIdentity, docKey) {
            if (file == null || !inWindow) return@LaunchedEffect
            val cacheKey = "${renderIdentity.filePath ?: resolved.pdfFilename}#${resolved.sourcePage}#dark=${renderIdentity.preferDarkMode}"
            val cachedThumb = thumbnailCache.get(cacheKey)
            if (cachedThumb != null && !cachedThumb.isRecycled) {
                thumbnailBitmap = cachedThumb
                return@LaunchedEffect
            }
            val thumb = withContext(Dispatchers.IO) {
                engineCache.get(file).renderThumbnail(
                    pageIndex = (resolved.sourcePage - 1).coerceAtLeast(0),
                    matteColorArgb = matteColorArgb
                )
            }
            if (thumb != null) {
                thumbnailCache.put(cacheKey, thumb)
                thumbnailBitmap = thumb
            }
        }

        // Sharp crop of just the on-screen slice of this page — only while zoomed in and only
        // once the gesture/scroll has settled, so scrolling fast past a page never queues up a
        // render that immediately gets thrown away.
        LaunchedEffect(
            renderIdentity,
            inWindow,
            zoomedIn,
            settled,
            sourceVariantChanged,
            sourceGeometryReady,
            matteColorArgb,
            pageCoordinates,
            contentCoordinates,
            contentSize,
            documentIdentity,
            docKey
        ) {
            val shouldRenderCrop = shouldRenderContinuousPdfCrop(
                    inWindow = inWindow,
                    zoomedIn = zoomedIn,
                    settled = settled,
                    sourceVariantChanged = sourceVariantChanged,
                    sourceGeometryReady = sourceGeometryReady
            )
            if (file == null || !shouldRenderCrop) return@LaunchedEffect
            val pc = pageCoordinates
            val host = contentCoordinates
            if (pc == null || host == null || !pc.isAttached || contentSize == IntSize.Zero) {
                return@LaunchedEffect
            }
            val rect = host.localBoundingBoxOf(pc, clipBounds = false)
            val frac = visiblePageFraction(
                pageLeft = rect.left,
                pageTop = rect.top,
                pageRight = rect.right,
                pageBottom = rect.bottom,
                viewportWidth = contentSize.width.toFloat(),
                viewportHeight = contentSize.height.toFloat()
            ) ?: return@LaunchedEffect
            val outputSize = resolveVisibleCropRenderSize(
                pageWidthPx = rect.right - rect.left,
                pageHeightPx = rect.bottom - rect.top,
                cropFrac = frac
            )
            if (outputSize == null) {
                cropOverlays.remove(displayPage)
                return@LaunchedEffect
            }
            val tile = withContext(Dispatchers.IO) {
                engineCache.get(file).renderCropFraction(
                    pageIndex = (resolved.sourcePage - 1).coerceAtLeast(0),
                    cropLeft = frac.left,
                    cropTop = frac.top,
                    cropRight = frac.right,
                    cropBottom = frac.bottom,
                    outputSize = outputSize,
                    matteColorArgb = matteColorArgb
                )
            }
            if (tile != null) {
                cropOverlays[displayPage] = ContinuousCropOverlay(
                    renderIdentity = renderIdentity,
                    bitmap = tile,
                    cropFrac = frac
                )
                lastRenderedCropVariant = preferDarkMode
            }
        }

        // markupStrokesForPage already scopes visibility (markupStrokesVisible) independent
        // of markupEnabled — gating on markupEnabled here as well would make drawn strokes
        // vanish the instant the pen is toggled off.
        val strokes = markupStrokesForPage(resolved.pdfFilename, resolved.sourcePage)
        // This page's ink overlay is NOT composed here: when zoomed, the sharp crops are drawn
        // outside (above) the zoom layer, so an overlay inside the page would be covered by
        // them. Register what the outer layer needs instead; ContinuousInkOverlayLayer draws
        // it above the crop canvas at every zoom.
        val inkPage = if (markupToolState != null && onMarkupStrokeAdded != null && onMarkupStrokeErased != null &&
            (markupEnabled || strokes.isNotEmpty())
        ) {
            ContinuousInkPage(
                pdfFilename = resolved.pdfFilename,
                sourcePage = resolved.sourcePage,
                aspectRatio = aspectRatio,
                pageSize = boxSize,
                strokes = strokes
            )
        } else {
            null
        }
        SideEffect {
            // Only write on a real change: this item recomposes on every pan/zoom frame, and an
            // unconditional put would recompose the whole ink layer with it.
            if (inkPage == null) {
                if (displayPage in inkPagesByDisplayPage) inkPagesByDisplayPage.remove(displayPage)
            } else if (inkPagesByDisplayPage[displayPage] != inkPage) {
                inkPagesByDisplayPage[displayPage] = inkPage
            }
        }

        // Fit the page within BOTH the pane's width AND height (like ContentScale.Fit), not
        // just width — a plain fillMaxWidth+aspectRatio box lets a portrait page in a short
        // (landscape-shaped) pane grow taller than the viewport, so its top/bottom get sliced
        // off by the pane's own clip bounds instead of shrinking to stay fully visible. Paged
        // mode (ReferencePdfPane) never has this because ContentScale.Fit guarantees the whole
        // page fits; continuous mode needs the same guarantee at rest, or a page's edges bleed
        // under the divider controls / nav bar chrome above and below the pane.
        val density = LocalDensity.current
        val ratio = aspectRatio ?: (8.5f / 11f)
        val topPadPx = with(density) { contentPadding.calculateTopPadding().roundToPx() }
        val bottomPadPx = with(density) { contentPadding.calculateBottomPadding().roundToPx() }
        val availW = paneSize.width
        val availH = (paneSize.height - topPadPx - bottomPadPx).coerceAtLeast(0)
        val (targetWPx, targetHPx) = if (availW <= 0 || availH <= 0) {
            val fallbackW = 1080
            fallbackW to (fallbackW / ratio).roundToInt()
        } else {
            val widthConstrainedH = (availW / ratio).roundToInt()
            if (widthConstrainedH <= availH) {
                availW to widthConstrainedH
            } else {
                val h = availH
                h.let { (it * ratio).roundToInt() to it }
            }
        }
        val targetWidthDp = with(density) { targetWPx.toDp() }
        val targetHeightDp = with(density) { targetHPx.toDp() }

        Box(
            // First in the chain so it runs on every re-placement of this node, which in
            // LazyColumn is the item root the list moves on scroll (LazyRow's wrapper below
            // carries the same hook). See ContinuousInkOverlayPlacer.requestPlacement.
            modifier = Modifier
                .onPlaced { inkOverlayPlacer.requestPlacement() }
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = targetWidthDp, height = targetHeightDp)
                    .onSizeChanged { boxSize = it }
                    .onGloballyPositioned {
                        pageCoordinates = it
                        pageCoordinatesByDisplayPage[displayPage] = it
                    }
            ) {
                PageBitmapLayers(
                    baseBitmap = baseBitmap,
                    thumbnailBitmap = thumbnailBitmap,
                    emptyCanvasColor = emptyCanvasColor,
                    contentDescription = "Page $displayPage"
                )
            }
        }
    }

    // With ink on, the stylus draws through each visible page's PdfMarkupOverlay (composed in
    // the outer layer above the crop canvas, but still a descendant of this handler) while fingers keep
    // scrolling and pinching through our handler below — the handler skips stylus pointers, and
    // the overlay ignores finger input unless finger drawing is on. Only then is our handler
    // removed, so touches pass straight through to the overlay and the list can't scroll under
    // a drawing finger. The
    // list's own userScrollEnabled stays false at all times regardless, because scrolling is
    // otherwise always driven programmatically by our handler rather than the list's built-in
    // touch handling — that built-in handling is what used to race against each page's own
    // independent pinch detector.
    val gesturesEnabled = shouldContinuousPaneOwnFingerGestures(
        markupEnabled = markupEnabled,
        allowFingerDrawing = markupToolState?.allowFingerDrawing == true
    )

    Box(
        modifier = modifier
            .onSizeChanged { paneSize = it }
            .onGloballyPositioned { paneRootCoordinates = it }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // contentPadding applied BEFORE the gesture modifier (not after, like the old
                // single padded Box below) so the gesture hit-region excludes the top/bottom
                // chrome bands (floating pill, divider controls) — same as ReferencePdfPane
                // (paged mode), which pads first for exactly this reason. Without this, the
                // gesture owner's awaitFirstDown(requireUnconsumed = false) swallows taps meant
                // for the pill's fullscreen/nav buttons that visually float on top of it, since
                // those buttons are a separate sibling composable outside this pane's subtree —
                // paged mode's fullscreen button works today only because its own pointerInput
                // is already scoped past the padding; continuous mode's wasn't.
                .padding(contentPadding)
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
                    Modifier.pointerInput(orientation, documentIdentity) {
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
                            // With ink on, pen strokes belong to PdfMarkupOverlay: never scroll,
                            // zoom, fling or tap-toggle chrome for a stylus. With ink off the
                            // overlay ignores input, so the pen scrolls like a finger. awaitEachGesture
                            // waits for every pointer to lift before it starts the next gesture.
                            // A pen touch still stops a running fling, like a finger touch does,
                            // so the page doesn't scroll under the stroke.
                            if (currentMarkupEnabled && isStylusPointerType(firstDown.type)) return@awaitEachGesture
                            isInteracting = true
                            var wasMultiTouch = false
                            var stylusTookOver = false
                            var totalMovement = 0f
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    // A pen landing mid-gesture: if a palm is already down on this
                                    // page, its overlay refused the palm's ACTION_DOWN and won't see
                                    // a pen on the same page until every pointer lifts — but a pen
                                    // landing on a DIFFERENT page's overlay gets its own ACTION_DOWN
                                    // and draws immediately. Breaking here, before this event's
                                    // changes are consumed, is what lets that stroke through instead
                                    // of being cancelled by our own pan/zoom consume() below.
                                    if (currentMarkupEnabled && event.changes.any { it.pressed && isStylusPointerType(it.type) }) {
                                        stylusTookOver = true
                                        break
                                    }
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    totalMovement += abs(panChange.x) + abs(panChange.y)
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
                                            // Zoom just changed this frame (possibly with no pan at
                                            // all) — reclamp any existing boundary overscroll to the
                                            // new zoom's bound so pinching back out shrinks it, same
                                            // as sharedCrossPan's reclamp above.
                                            val maxMainOverscroll = mainAxisEdgePadding(viewH.toFloat(), next.zoom)
                                            sharedMainAxisOverscroll = sharedMainAxisOverscroll.coerceIn(-maxMainOverscroll, maxMainOverscroll)
                                            continuousMainAxisScrollDelta(
                                                panDelta = next.panY,
                                                zoom = next.zoom,
                                                viewportExtent = viewH
                                            )?.let { scrollDeltaChannel.trySend(it) }
                                        }
                                        Orientation.Horizontal -> {
                                            val maxCross = maxCrossAxisPan(viewH.toFloat(), next.zoom)
                                            sharedCrossPan = next.panY.coerceIn(-maxCross, maxCross)
                                            val maxMainOverscroll = mainAxisEdgePadding(viewW.toFloat(), next.zoom)
                                            sharedMainAxisOverscroll = sharedMainAxisOverscroll.coerceIn(-maxMainOverscroll, maxMainOverscroll)
                                            continuousMainAxisScrollDelta(
                                                panDelta = next.panX,
                                                zoom = next.zoom,
                                                viewportExtent = viewW
                                            )?.let { scrollDeltaChannel.trySend(it) }
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            } finally {
                                isInteracting = false
                            }
                            if (stylusTookOver) return@awaitEachGesture

                            // Plain tap: single pointer, negligible movement — same threshold
                            // (touchSlop) Compose's own tap/click detectors use to distinguish a
                            // tap from a drag. Toggles the floating pill/nav-bar chrome, matching
                            // paged mode's ReferencePdfPane (which wires this via its own
                            // detectTapGestures) — continuous mode never had a tap path at all.
                            // Not while inking: the chrome holds the ink toolbar, and a stray
                            // finger tap must not hide it mid-markup.
                            if (!currentMarkupEnabled && !wasMultiTouch && totalMovement <= touchSlop) {
                                currentOnSingleTap?.invoke()
                            }

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

                            if (abs(flingVelocity) > 100f || abs(crossFlingVelocity) > 100f) {
                                // Post a NaN sentinel to force the drag consumer to exit its
                                // listState.scroll() session.
                                // The coalescer delivers any final pending drag movement before
                                // this sentinel so no active gesture distance is lost at handoff.
                                scrollDeltaChannel.trySend(Float.NaN)  // sentinel: release drag session
                                flingJob = documentFlingScope.launch {
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
                                                        applyMainAxisScroll(mainStep.delta)
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
                                        }
                                    } finally {
                                        isFlinging = false
                                    }
                                }
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
                .clipToBounds()
                .onSizeChanged { contentSize = it }
                .onGloballyPositioned { contentCoordinates = it }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                    scaleX = sharedZoom
                    scaleY = sharedZoom
                    // Main-axis overscroll is stored in list-space (unscaled) units, same
                    // convention as ScrollScope.scrollBy — re-expand to screen px by the live
                    // zoom, matching how everything else in this whole-stack transform scales.
                    // See sharedMainAxisOverscroll's doc comment for the sign derivation.
                    val overscrollScreenPx = -sharedMainAxisOverscroll * sharedZoom
                    when (orientation) {
                        Orientation.Vertical -> {
                            translationX = sharedCrossPan
                            translationY = overscrollScreenPx
                        }
                        Orientation.Horizontal -> {
                            translationY = sharedCrossPan
                            translationX = overscrollScreenPx
                        }
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
                            // The wrapper, not pageContent's root, is the node LazyRow moves.
                            Box(
                                Modifier
                                    .onPlaced { inkOverlayPlacer.requestPlacement() }
                                    .fillParentMaxWidth()
                            ) { pageContent(index + 1) }
                        }
                    }
                }
            }
            // The crop overlay is outside the graphicsLayer, so it must observe every state input to
            // that layer (and LazyList's live position) to map its retained PDF fraction again
            // on every pan/zoom frame.
            val overlayMotionFrame = sharedZoom + sharedCrossPan + sharedMainAxisOverscroll +
                listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset
            Canvas(Modifier.fillMaxSize()) {
                cropOverlays.values.forEach { overlay ->
                    val pageCoordinates = pageCoordinatesByDisplayPage[overlay.renderIdentity.displayPage]
                    val host = contentCoordinates
                    if (overlayMotionFrame.isFinite() && zoomedIn &&
                        overlay.renderIdentity.preferDarkMode == preferDarkMode &&
                        pageCoordinates != null && host != null && pageCoordinates.isAttached
                    ) {
                        val pageBounds = host.localBoundingBoxOf(pageCoordinates, clipBounds = false)
                        val bounds = resolveContinuousCropOverlayBounds(
                            pageLeftPx = pageBounds.left,
                            pageTopPx = pageBounds.top,
                            pageRightPx = pageBounds.right,
                            pageBottomPx = pageBounds.bottom,
                            cropFrac = overlay.cropFrac
                        )
                        drawImage(
                            image = overlay.bitmap.asImageBitmap(),
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(overlay.bitmap.width, overlay.bitmap.height),
                            dstOffset = IntOffset(bounds.leftPx.roundToInt(), bounds.topPx.roundToInt()),
                            dstSize = IntSize(
                                bounds.widthPx.roundToInt().coerceAtLeast(1),
                                bounds.heightPx.roundToInt().coerceAtLeast(1)
                            ),
                            filterQuality = FilterQuality.None
                        )
                    }
                }
            }
            // Ink goes last so it always draws above the sharp crops (committed strokes AND the
            // live stroke), at every zoom including 1x — one code path. Each overlay is sized
            // and placed over its page's live on-screen bounds by the placer's layout node; it
            // stays a descendant of the gesture Box above, so finger scroll/zoom routing is
            // unchanged.
            if (markupToolState != null && onMarkupStrokeAdded != null && onMarkupStrokeErased != null) {
                ContinuousInkOverlayLayer(
                    inkPages = inkPagesByDisplayPage,
                    isPageVisible = { it in visiblePages },
                    placer = inkOverlayPlacer,
                    zoom = sharedZoom,
                    markupEnabled = markupEnabled,
                    markupToolState = markupToolState,
                    onMarkupStrokeAdded = onMarkupStrokeAdded,
                    onMarkupStrokeErased = onMarkupStrokeErased
                )
            }
        }
        }
    }
}

/**
 * What the outer ink layer needs to compose one page's [PdfMarkupOverlay], registered by that
 * page's list item. [pageSize] is the page box's unscaled layout size (before the whole-stack
 * zoom layer).
 */
@Immutable
internal data class ContinuousInkPage(
    val pdfFilename: String,
    val sourcePage: Int,
    val aspectRatio: Float?,
    val pageSize: IntSize,
    val strokes: List<PdfInkStroke>
)

/**
 * On-screen size of a page box under the whole-stack zoom layer (which scales the box by exactly
 * [zoom]). Used as the ink overlay's own size and view box, so normalized stroke points map onto
 * the page's scaled rect.
 */
internal fun continuousInkOverlayViewSize(pageSize: IntSize, zoom: Float): IntSize {
    if (pageSize.width <= 0 || pageSize.height <= 0 || !zoom.isFinite() || zoom <= 0f) return IntSize.Zero
    return IntSize(
        (pageSize.width * zoom).roundToInt().coerceAtLeast(1),
        (pageSize.height * zoom).roundToInt().coerceAtLeast(1)
    )
}

/** Pixel offset for an ink overlay whose page's on-screen bounds start at ([leftPx], [topPx]). */
internal fun continuousInkOverlayOffset(leftPx: Float, topPx: Float): IntOffset =
    IntOffset(leftPx.roundToInt(), topPx.roundToInt())

// Where an overlay goes while its page has no usable coordinates for a moment: fully outside the
// clipped content box (so nothing draws and nothing can be hit), with slack for stroke width.
private const val CONTINUOUS_INK_PARKED_MARGIN_PX = 4096

internal fun continuousInkParkedOffset(width: Int, height: Int): IntOffset =
    IntOffset(-(width + CONTINUOUS_INK_PARKED_MARGIN_PX), -(height + CONTINUOUS_INK_PARKED_MARGIN_PX))

/**
 * Places each page's ink overlay (a sibling of the zoom layer, above the crop canvas) over that
 * page's live bounds inside the clipped content box ([hostCoordinates]).
 *
 * Position is resolved in the PLACEMENT phase, so pan/zoom/scroll frames only re-place the
 * overlays (each on its own layer, so no redraw) and never recompose them. Two triggers:
 *  - The placement block reads [motionFrame] (zoom, cross pan, edge overscroll, list scroll).
 *    graphicsLayer parameter changes are applied before layout, so zoom/pan frames place
 *    correctly from this alone.
 *  - LazyList scroll moves items in its OWN placement pass, which runs after ours (the list's
 *    nodes are deeper, and relayout is processed shallow-first). Reading the page coordinates in
 *    our pass alone would lag one frame, and stay wrong once scrolling stops. So each list item
 *    calls [requestPlacement] from `onPlaced`, which re-queues our nodes directly (no snapshot
 *    round-trip) and the same layout pass re-places them with the fresh item positions.
 */
internal class ContinuousInkOverlayPlacer(
    private val pageCoordinatesByDisplayPage: Map<Int, LayoutCoordinates>,
    private val hostCoordinates: () -> LayoutCoordinates?,
    private val motionFrame: () -> Float
) {
    private val nodes = ArrayList<ContinuousInkPlacementNode>()

    internal fun register(node: ContinuousInkPlacementNode) {
        if (!nodes.contains(node)) nodes.add(node)
    }

    internal fun unregister(node: ContinuousInkPlacementNode) {
        nodes.remove(node)
    }

    fun requestPlacement() {
        for (i in nodes.indices) nodes[i].requestPlacement()
    }

    internal fun pageOffset(displayPage: Int): IntOffset? {
        // Read for its snapshot subscription: any pan/zoom/scroll state change re-places.
        if (!motionFrame().isFinite()) return null
        val page = pageCoordinatesByDisplayPage[displayPage] ?: return null
        val host = hostCoordinates() ?: return null
        if (!page.isAttached || !host.isAttached) return null
        val bounds = host.localBoundingBoxOf(page, clipBounds = false)
        return continuousInkOverlayOffset(bounds.left, bounds.top)
    }
}

private fun Modifier.continuousInkPagePlacement(
    placer: ContinuousInkOverlayPlacer,
    displayPage: Int,
    viewSize: IntSize
): Modifier = this then ContinuousInkPlacementElement(placer, displayPage, viewSize)

private class ContinuousInkPlacementElement(
    val placer: ContinuousInkOverlayPlacer,
    val displayPage: Int,
    val viewSize: IntSize
) : ModifierNodeElement<ContinuousInkPlacementNode>() {
    override fun create() = ContinuousInkPlacementNode(placer, displayPage, viewSize)

    override fun update(node: ContinuousInkPlacementNode) {
        if (node.placer !== placer) {
            if (node.isAttached) {
                node.placer.unregister(node)
                placer.register(node)
            }
            node.placer = placer
        }
        node.displayPage = displayPage
        node.viewSize = viewSize
    }

    override fun equals(other: Any?): Boolean =
        other is ContinuousInkPlacementElement &&
            other.placer === placer &&
            other.displayPage == displayPage &&
            other.viewSize == viewSize

    override fun hashCode(): Int {
        var result = System.identityHashCode(placer)
        result = 31 * result + displayPage
        result = 31 * result + viewSize.hashCode()
        return result
    }
}

internal class ContinuousInkPlacementNode(
    var placer: ContinuousInkOverlayPlacer,
    var displayPage: Int,
    var viewSize: IntSize
) : Modifier.Node(), LayoutModifierNode {
    override fun onAttach() {
        placer.register(this)
    }

    override fun onDetach() {
        placer.unregister(this)
    }

    fun requestPlacement() {
        if (isAttached) invalidatePlacement()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        // The content (the overlay's pointer filter + canvas) is exactly the page's on-screen
        // size; this node itself spans the content box so the Box parent never re-centers an
        // oversized (zoomed) child. Hit testing uses the content's bounds, not this node's.
        val placeable = measurable.measure(Constraints.fixed(viewSize.width, viewSize.height))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
        return layout(width, height) {
            val offset = placer.pageOffset(displayPage)
                ?: continuousInkParkedOffset(placeable.width, placeable.height)
            // Own layer: moving it each frame only updates the layer offset; the stroke paths
            // are not re-recorded.
            placeable.placeWithLayer(offset)
        }
    }
}

/**
 * Every visible page's [PdfMarkupOverlay], composed as siblings drawn after the sharp crop
 * canvas. A restartable, skippable composable of its own: pan and scroll frames change none of
 * its arguments, so they only re-place the overlays (see [ContinuousInkOverlayPlacer]). [zoom]
 * does change during a pinch; the overlays' view size and stroke width depend on it, and the
 * pane already recomposes on those frames.
 */
@Composable
private fun ContinuousInkOverlayLayer(
    inkPages: Map<Int, ContinuousInkPage>,
    isPageVisible: (Int) -> Boolean,
    placer: ContinuousInkOverlayPlacer,
    zoom: Float,
    markupEnabled: Boolean,
    markupToolState: PdfMarkupToolState,
    onMarkupStrokeAdded: (sourceFilename: String, sourcePage: Int, PdfInkStroke) -> Unit,
    onMarkupStrokeErased: (sourceFilename: String, sourcePage: Int, strokeId: String) -> Unit
) {
    val strokeWidthScale = zoom.coerceAtLeast(1f)
    inkPages.forEach { (displayPage, page) ->
        val viewSize = continuousInkOverlayViewSize(page.pageSize, zoom)
        if (isPageVisible(displayPage) && viewSize != IntSize.Zero) {
            key(displayPage, page.pdfFilename, page.sourcePage) {
                PdfMarkupOverlay(
                    modifier = Modifier.continuousInkPagePlacement(placer, displayPage, viewSize),
                    // The overlay's box IS the page's scaled on-screen rect, so no further zoom or
                    // pan: normalized points map straight onto it (same aspect ratio as the page).
                    viewportState = PdfViewportState(zoom = 1f, panX = 0f, panY = 0f, viewSize = viewSize),
                    pageAspectRatio = page.aspectRatio,
                    activeStrokes = page.strokes,
                    inputEnabled = markupEnabled,
                    activeTool = markupToolState.activeTool,
                    activeColor = markupToolState.activeColor,
                    activeThickness = markupToolState.activeThickness,
                    allowFingerDrawing = markupToolState.allowFingerDrawing,
                    onStylusButtonEraserChanged = { markupToolState.isStylusButtonEraserActive = it },
                    onStrokeAdded = { stroke -> onMarkupStrokeAdded(page.pdfFilename, page.sourcePage, stroke) },
                    onStrokeErased = { strokeId -> onMarkupStrokeErased(page.pdfFilename, page.sourcePage, strokeId) },
                    strokeWidthScale = strokeWidthScale
                )
            }
        }
    }
}
