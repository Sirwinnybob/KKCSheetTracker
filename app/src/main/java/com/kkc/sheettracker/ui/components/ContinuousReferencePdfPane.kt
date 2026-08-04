package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
        if (totalPages > 0 && scrollToPage in 1..totalPages) {
            listState.scrollToItem((scrollToPage - 1).coerceIn(0, totalPages - 1))
        }
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

    val isSettled by remember {
        derivedStateOf { !listState.isScrollInProgress }
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

        LaunchedEffect(displayPage, resolved, file, inWindow, isSettled, matteColorArgb, fileIdentitySeed) {
            if (file == null || !inWindow || !isSettled) return@LaunchedEffect
            if (bitmap != null) return@LaunchedEffect
            // Settle-only: waits for scroll to stop before spending render work, same
            // debounce intent as ReferencePdfPane's zoom-detail-tile flow.
            delay(120)
            if (!inWindow || listState.isScrollInProgress) return@LaunchedEffect
            val viewSize = androidx.compose.ui.unit.IntSize(1080, 1400)
            bitmap = withContext(Dispatchers.IO) {
                engineCache.get(file).renderBasePage(
                    pageIndex = (resolved.sourcePage - 1).coerceAtLeast(0),
                    viewSize = viewSize,
                    matteColorArgb = matteColorArgb
                )
            }
        }

        val strokes = if (markupEnabled) markupStrokesForPage(resolved.pdfFilename, resolved.sourcePage) else emptyList()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio ?: (8.5f / 11f))
                .onSizeChanged { boxSize = it }
        ) {
            val currentBitmap = bitmap
            if (currentBitmap != null) {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = "Page $displayPage",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
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
