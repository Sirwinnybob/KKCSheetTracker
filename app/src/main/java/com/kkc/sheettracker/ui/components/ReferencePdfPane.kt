package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.ui.markup.PdfMarkupOverlay
import com.kkc.sheettracker.ui.markup.PdfMarkupToolState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.GlobalScope
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PdfViewportState(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val viewSize: IntSize = IntSize.Zero
)

internal enum class SideGutterTapRegion {
    LEFT,
    RIGHT,
    NONE
}



sealed interface PdfRenderUiState {
    data object Loading : PdfRenderUiState
    data class Ready(val bitmap: Bitmap) : PdfRenderUiState
    data class Error(val message: String) : PdfRenderUiState
}

class PdfRenderEngine(private val pdfFile: File) {
    private val mutex = Mutex()
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    private fun ensureRendererLocked(): PdfRenderer {
        renderer?.let { return it }
        val openedFd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val openedRenderer = PdfRenderer(openedFd)
        fd = openedFd
        renderer = openedRenderer
        return openedRenderer
    }

    suspend fun pageCount(): Int = mutex.withLock {
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@withLock 0
        return if (!pdfFile.exists()) 0 else {
            try {
                ensureRendererLocked().pageCount
            } catch (e: Exception) {
                0
            }
        }
    }

    suspend fun renderViewportTile(
        pageIndex: Int,
        viewport: PdfViewportState,
        matteColorArgb: Int = android.graphics.Color.WHITE
    ): Bitmap? = mutex.withLock {
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@withLock null
        if (!pdfFile.exists() || viewport.viewSize == IntSize.Zero) return null
        val localRenderer = ensureRendererLocked()
        if (pageIndex !in 0 until localRenderer.pageCount) return null
        var page: PdfRenderer.Page? = null
        try {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@withLock null
            page = localRenderer.openPage(pageIndex)
            val activePage = page
            val viewWidth = viewport.viewSize.width.coerceAtLeast(1)
            val viewHeight = viewport.viewSize.height.coerceAtLeast(1)
            val outputScale = when {
                viewport.zoom >= 5f -> 2.0f
                viewport.zoom >= 2.5f -> 1.5f
                else -> 1.2f
            }
            var outWidth = (viewWidth * outputScale).toInt().coerceAtLeast(1)
            var outHeight = (viewHeight * outputScale).toInt().coerceAtLeast(1)
            val maxArea = 16_000_000
            val area = outWidth.toLong() * outHeight.toLong()
            if (area > maxArea) {
                val down = sqrt(area.toDouble() / maxArea.toDouble()).toFloat()
                outWidth = (outWidth / down).toInt().coerceAtLeast(1)
                outHeight = (outHeight / down).toInt().coerceAtLeast(1)
            }

            val pageWidth = activePage.width.toFloat().coerceAtLeast(1f)
            val pageHeight = activePage.height.toFloat().coerceAtLeast(1f)
            val baseScale = minOf(viewWidth / pageWidth, viewHeight / pageHeight)
            val pageDisplayWidth = pageWidth * baseScale
            val pageDisplayHeight = pageHeight * baseScale
            val offsetX = (viewWidth - pageDisplayWidth) / 2f
            val offsetY = (viewHeight - pageDisplayHeight) / 2f
            val cx = viewWidth / 2f
            val cy = viewHeight / 2f
            val zoom = viewport.zoom.coerceIn(1f, 14f)

            fun viewToPage(xView: Float, yView: Float): Pair<Float, Float> {
                val xBase = ((xView - cx - viewport.panX) / zoom) + cx
                val yBase = ((yView - cy - viewport.panY) / zoom) + cy
                val pageX = ((xBase - offsetX) / baseScale).coerceIn(0f, pageWidth)
                val pageY = ((yBase - offsetY) / baseScale).coerceIn(0f, pageHeight)
                return pageX to pageY
            }

            val topLeft = viewToPage(0f, 0f)
            val bottomRight = viewToPage(viewWidth.toFloat(), viewHeight.toFloat())
            val srcLeft = minOf(topLeft.first, bottomRight.first)
            val srcTop = minOf(topLeft.second, bottomRight.second)
            val srcRight = maxOf(topLeft.first, bottomRight.first)
            val srcBottom = maxOf(topLeft.second, bottomRight.second)
            val srcWidth = (srcRight - srcLeft).coerceAtLeast(1f)
            val srcHeight = (srcBottom - srcTop).coerceAtLeast(1f)

            val bmp = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(matteColorArgb)
            val uniformScale = minOf(outWidth / srcWidth, outHeight / srcHeight)
            val bitmapOffsetX = (outWidth - srcWidth * uniformScale) / 2f
            val bitmapOffsetY = (outHeight - srcHeight * uniformScale) / 2f
            val matrix = Matrix().apply {
                postTranslate(-srcLeft, -srcTop)
                postScale(uniformScale, uniformScale)
                postTranslate(bitmapOffsetX, bitmapOffsetY)
            }
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                bmp.recycle()
                return@withLock null
            }
            activePage.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            runCatching { page?.close() }
        }
    }

    suspend fun pageAspectRatio(pageIndex: Int): Float? = mutex.withLock {
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@withLock null
        if (!pdfFile.exists()) return null
        val localRenderer = ensureRendererLocked()
        if (pageIndex !in 0 until localRenderer.pageCount) return null
        var page: PdfRenderer.Page? = null
        return try {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@withLock null
            page = localRenderer.openPage(pageIndex)
            val activePage = page
            val width = activePage.width.toFloat().coerceAtLeast(1f)
            val height = activePage.height.toFloat().coerceAtLeast(1f)
            width / height
        } finally {
            runCatching { page?.close() }
        }
    }

    suspend fun renderBasePage(
        pageIndex: Int,
        viewSize: IntSize,
        qualityScale: Float = 1.1f,
        matteColorArgb: Int = android.graphics.Color.WHITE
    ): Bitmap? = mutex.withLock {
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@withLock null
        if (!pdfFile.exists() || viewSize == IntSize.Zero) return null
        val localRenderer = ensureRendererLocked()
        if (pageIndex !in 0 until localRenderer.pageCount) return null
        var page: PdfRenderer.Page? = null
        try {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@withLock null
            page = localRenderer.openPage(pageIndex)
            val activePage = page
            val pageWidth = activePage.width.toFloat().coerceAtLeast(1f)
            val pageHeight = activePage.height.toFloat().coerceAtLeast(1f)
            val viewWidth = viewSize.width.coerceAtLeast(1)
            val viewHeight = viewSize.height.coerceAtLeast(1)
            val fitScale = minOf(viewWidth / pageWidth, viewHeight / pageHeight).coerceAtLeast(0.01f)
            var outWidth = (pageWidth * fitScale * qualityScale).toInt().coerceAtLeast(1)
            var outHeight = (pageHeight * fitScale * qualityScale).toInt().coerceAtLeast(1)
            val maxArea = 8_000_000
            val area = outWidth.toLong() * outHeight.toLong()
            if (area > maxArea) {
                val down = sqrt(area.toDouble() / maxArea.toDouble()).toFloat()
                outWidth = (outWidth / down).toInt().coerceAtLeast(1)
                outHeight = (outHeight / down).toInt().coerceAtLeast(1)
            }
            val bmp = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(matteColorArgb)
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                bmp.recycle()
                return@withLock null
            }
            activePage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally {
            runCatching { page?.close() }
        }
    }

    suspend fun renderThumbnail(pageIndex: Int, maxWidth: Int = 340): Bitmap? = mutex.withLock {
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@withLock null
        if (!pdfFile.exists()) return null
        val localRenderer = ensureRendererLocked()
        if (pageIndex !in 0 until localRenderer.pageCount) return null
        var page: PdfRenderer.Page? = null
        try {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) return@withLock null
            page = localRenderer.openPage(pageIndex)
            val activePage = page
            val scale = maxWidth.toFloat() / activePage.width.toFloat().coerceAtLeast(1f)
            val width = (activePage.width * scale).toInt().coerceAtLeast(1)
            val height = (activePage.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                bmp.recycle()
                return@withLock null
            }
            activePage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            runCatching { page?.close() }
        }
    }

    suspend fun close() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { renderer?.close() }
            runCatching { fd?.close() }
            renderer = null
            fd = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReferencePdfPane(
    modifier: Modifier = Modifier,
    pdfFile: File?,
    currentPage: Int,
    onCurrentPageChange: (Int) -> Unit,
    showDocControls: (@Composable RowScope.() -> Unit)? = null,
    missingText: String = "Reference PDF not found",
    unreadableText: String = "Unable to read PDF",
    onTotalPagesChanged: (Int) -> Unit = {},
    onViewportStateChange: (PdfViewportState) -> Unit = {},
    showHeaderRow: Boolean = true,
    showNavigationButtons: Boolean = true,
    innerPadding: Dp = 8.dp,
    tocRequestToken: Int = 0,
    displayPageOverride: Int? = null,
    displayTotalPagesOverride: Int? = null,
    onStepPage: ((Int) -> Unit)? = null,
    onOpenSheetNavigator: (() -> Unit)? = null,
    onSingleTap: (() -> Unit)? = null,
    compactArrows: Boolean = false,
    preferDarkMode: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    markupEnabled: Boolean = false,
    onToggleMarkupEnabled: (() -> Unit)? = null,
    markupToolState: PdfMarkupToolState? = null,
    markupStrokes: List<PdfInkStroke> = emptyList(),
    onMarkupStrokeAdded: ((PdfInkStroke) -> Unit)? = null,
    onMarkupStrokeErased: ((String) -> Unit)? = null
) {
    val pdfIdentityKey = when {
        pdfFile == null -> "missing"
        !pdfFile.exists() -> "missing:${pdfFile.absolutePath}"
        else -> "${pdfFile.absolutePath}|${pdfFile.length()}|${pdfFile.lastModified()}"
    }
    val engine = remember(pdfIdentityKey) { pdfFile?.takeIf { it.exists() }?.let { PdfRenderEngine(it) } }
    // Plain size-bounded cache. Do NOT recycle on eviction: asImageBitmap() shares the buffer with a
    // live Compose Image, so recycling an evicted-but-still-drawn page crashes with
    // "Canvas: trying to use a recycled bitmap". GC reclaims evicted pages safely.
    val basePageCache = remember(pdfIdentityKey) { LruCache<Int, Bitmap>(6) }
    DisposableEffect(engine) {
        onDispose {
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            engine?.let { e -> GlobalScope.launch { e.close() } }
            basePageCache.evictAll()
        }
    }

    var totalPages by remember(engine) { mutableIntStateOf(0) }
    var viewportState by remember(engine) { mutableStateOf(PdfViewportState()) }
    var isInteracting by remember(engine) { mutableStateOf(false) }
    var pageAspectRatio by remember(engine, currentPage) { mutableStateOf<Float?>(null) }
    var renderState by remember(engine, currentPage) { mutableStateOf<PdfRenderUiState>(PdfRenderUiState.Loading) }
    val matteColorArgb = if (preferDarkMode) MaterialTheme.colorScheme.surface.toArgb() else android.graphics.Color.WHITE
    var baseBitmap by remember(engine, currentPage) { mutableStateOf<Bitmap?>(null) }
    var detailBitmap by remember(engine, currentPage) { mutableStateOf<Bitmap?>(null) }
    var detailForViewport by remember(engine, currentPage) { mutableStateOf<QuantizedViewportState?>(null) }
    // Fallback bitmap: the last successfully rendered page bitmap. Shown while the new page
    // is still rendering to prevent a blank-screen flash on page transitions.
    var fallbackBitmap by remember(engine) { mutableStateOf<Bitmap?>(null) }
    // Slide animation state: direction (-1 back / 1 fwd), captured outgoing bitmap, and progress.
    var pageSlideDir by remember { mutableIntStateOf(0) }
    var slideOutBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val slideProgress = remember { Animatable(1f) }
    val paneScope = rememberCoroutineScope()
    var showToc by remember { mutableStateOf(false) }
    val tocThumbCache = remember(engine) { mutableStateMapOf<Int, Bitmap?>() }
    val tocLruOrder = remember(engine) { mutableListOf<Int>() }
    val tocLoadedCount by remember { derivedStateOf { tocThumbCache.count { it.value != null } } }

    LaunchedEffect(engine) {
        totalPages = withContext(Dispatchers.IO) { engine?.pageCount() ?: 0 }
        val clamped = currentPage.coerceIn(1, totalPages.coerceAtLeast(1))
        if (clamped != currentPage) onCurrentPageChange(clamped)
        onTotalPagesChanged(totalPages)
    }

    LaunchedEffect(tocRequestToken, totalPages) {
        if (tocRequestToken > 0) {
            if (onOpenSheetNavigator != null) {
                onOpenSheetNavigator()
            } else if (totalPages > 0) {
                showToc = true
            }
        }
    }

    LaunchedEffect(currentPage) {
        viewportState = viewportState.copy(zoom = 1f, panX = 0f, panY = 0f)
        detailBitmap = null
        detailForViewport = null
    }
    // Keep fallbackBitmap up-to-date so new pages fade in over the previous page.
    SideEffect {
        if (baseBitmap != null) fallbackBitmap = baseBitmap
    }

    SideEffect {
        onViewportStateChange(viewportState)
    }

    LaunchedEffect(engine, currentPage, totalPages) {
        if (engine == null || totalPages <= 0) {
            pageAspectRatio = null
            return@LaunchedEffect
        }
        pageAspectRatio = withContext(Dispatchers.IO) {
            engine.pageAspectRatio((currentPage - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(engine, currentPage, totalPages, viewportState.viewSize, matteColorArgb) {
        if (engine == null || totalPages <= 0) {
            renderState = PdfRenderUiState.Error(unreadableText)
            return@LaunchedEffect
        }
        val viewSize = viewportState.viewSize
        if (viewSize == IntSize.Zero) return@LaunchedEffect
        val cached = basePageCache.get(currentPage)
        if (cached != null && !cached.isRecycled) {
            baseBitmap = cached
            return@LaunchedEffect
        }
        val renderedBase = withContext(Dispatchers.IO) {
            engine.renderBasePage(
                pageIndex = (currentPage - 1).coerceAtLeast(0),
                viewSize = viewSize,
                matteColorArgb = matteColorArgb
            )
        }
        if (renderedBase != null) {
            basePageCache.put(currentPage, renderedBase)
            baseBitmap = renderedBase
        }
    }

    LaunchedEffect(engine, currentPage, totalPages, matteColorArgb) {
        if (engine == null || totalPages <= 0) {
            renderState = PdfRenderUiState.Error(unreadableText)
            return@LaunchedEffect
        }
        snapshotFlow { viewportState to isInteracting }
            .distinctUntilChanged { old, new ->
                old.second == new.second && old.first.quantized() == new.first.quantized()
            }
            .debounce(120)
            .collectLatest { (viewport, interacting) ->
                if (viewport.viewSize == IntSize.Zero || interacting) return@collectLatest
                val quantized = viewport.quantized()
                renderState = PdfRenderUiState.Loading
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        engine.renderViewportTile(
                            pageIndex = (currentPage - 1).coerceAtLeast(0),
                            viewport = viewport,
                            matteColorArgb = matteColorArgb
                        )
                    }
                    if (bitmap != null) {
                        detailBitmap = bitmap
                        detailForViewport = quantized
                        renderState = PdfRenderUiState.Ready(bitmap)
                    } else {
                        renderState = PdfRenderUiState.Error(unreadableText)
                    }
                } catch (e: Exception) {
                    renderState = PdfRenderUiState.Error("Render error: ${e.localizedMessage}")
                }
            }
    }

    if (showToc && onOpenSheetNavigator == null) {
        ReferenceTocSheet(
            pageCount = totalPages,
            currentPage = currentPage,
            loadedCount = tocLoadedCount,
            tocThumbCache = tocThumbCache,
            onSelectPage = {
                onCurrentPageChange(it)
                showToc = false
            },
            onDismiss = { showToc = false }
        )

        LaunchedEffect(showToc, totalPages, currentPage, engine) {
            if (!showToc || totalPages <= 0 || engine == null) return@LaunchedEffect
            for (page in buildReferenceTocLoadOrder(totalPages, currentPage)) {
                if (!isActive) break
                if (!tocThumbCache.containsKey(page)) {
                    val thumb = withContext(Dispatchers.IO) { engine.renderThumbnail(page - 1) }
                    tocThumbCache[page] = thumb
                    tocLruOrder.remove(page)
                    tocLruOrder.add(page)
                    trimTocCache(tocThumbCache, tocLruOrder, maxEntries = 80)
                }
                yield()
            }
        }
    }

    val displayPage = displayPageOverride ?: currentPage
    val displayTotalPages = (displayTotalPagesOverride ?: totalPages).coerceAtLeast(0)
    val stepPage: (Int) -> Unit = { delta ->
        if (delta < 0 && displayPage > 1) {
            pageSlideDir = -1
            slideOutBitmap = baseBitmap ?: fallbackBitmap
            paneScope.launch {
                slideProgress.snapTo(0f)
                slideProgress.animateTo(1f, animationSpec = tween(280, easing = FastOutSlowInEasing))
                slideOutBitmap = null
            }
            if (onStepPage != null) onStepPage(-1)
            else onCurrentPageChange((currentPage - 1).coerceAtLeast(1))
        } else if (delta > 0 && displayPage < displayTotalPages) {
            pageSlideDir = 1
            slideOutBitmap = baseBitmap ?: fallbackBitmap
            paneScope.launch {
                slideProgress.snapTo(0f)
                slideProgress.animateTo(1f, animationSpec = tween(280, easing = FastOutSlowInEasing))
                slideOutBitmap = null
            }
            if (onStepPage != null) onStepPage(1)
            else onCurrentPageChange((currentPage + 1).coerceAtMost(totalPages.coerceAtLeast(1)))
        }
    }

    Column(modifier = modifier.padding(innerPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeaderRow) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                showDocControls?.invoke(this)
                Spacer(Modifier.weight(1f))
                Text("Page $displayPage/$displayTotalPages", style = MaterialTheme.typography.bodySmall)
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 0.dp  // 0 so the container colour matches the PDF matte exactly
        ) {
            when {
                pdfFile == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(missingText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                totalPages <= 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(unreadableText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Box(Modifier.fillMaxSize().padding(contentPadding)) {
                        val vw = viewportState.viewSize.width.toFloat().coerceAtLeast(1f)
                        val isSliding = slideProgress.value < 1f
                        val shouldShowDetail = detailBitmap != null &&
                            detailForViewport == viewportState.quantized() &&
                            !isInteracting

                        // Old page slides out while the new page slides in.
                        val snapSlideOut = slideOutBitmap
                        if (snapSlideOut != null && !snapSlideOut.isRecycled) {
                            Image(
                                bitmap = snapSlideOut.asImageBitmap(),
                                contentDescription = "PDF Image",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationX = -slideProgress.value * vw * pageSlideDir
                                    },
                                contentScale = ContentScale.Fit
                            )
                        }

                        ZoomablePdfImage(
                            // Suppress fallback during slide — the slideOutBitmap layer covers it.
                            baseBitmap = baseBitmap ?: if (!isSliding) fallbackBitmap else null,
                            detailBitmap = if (shouldShowDetail) detailBitmap else null,
                            pageKey = currentPage,
                            onViewportStateChanged = { viewportState = it },
                            onInteractionChanged = { isInteracting = it },
                            pageAspectRatio = pageAspectRatio,
                            onGutterTapStep = null, // replaced by overlay arrow buttons
                            onSingleTap = onSingleTap,
                            allowStylusGestures = !markupEnabled,
                            allowFingerGestures = !markupEnabled || !(markupToolState?.allowFingerDrawing ?: false),
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // Deferred read: drives only a draw-phase update, no recomposition.
                                    translationX = (1f - slideProgress.value) * vw * pageSlideDir
                                }
                        )

                        if (markupToolState != null &&
                            onMarkupStrokeAdded != null &&
                            onMarkupStrokeErased != null &&
                            (markupEnabled || markupStrokes.isNotEmpty())
                        ) {
                            PdfMarkupOverlay(
                                modifier = Modifier.fillMaxSize(),
                                viewportState = viewportState,
                                pageAspectRatio = pageAspectRatio,
                                activeStrokes = markupStrokes,
                                inputEnabled = markupEnabled,
                                activeTool = markupToolState.activeTool,
                                activeColor = markupToolState.activeColor,
                                activeThickness = markupToolState.activeThickness,
                                allowFingerDrawing = markupToolState.allowFingerDrawing,
                                onStylusButtonEraserChanged = {
                                    markupToolState.isStylusButtonEraserActive = it
                                },
                                onStrokeAdded = onMarkupStrokeAdded,
                                onStrokeErased = onMarkupStrokeErased
                            )
                        }

                        if (onToggleMarkupEnabled != null) {
                            IconButton(
                                onClick = onToggleMarkupEnabled,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                                        shape = CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.Default.Create,
                                    contentDescription = if (markupEnabled) "Disable drawing" else "Enable drawing",
                                    tint = if (markupEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // ── Page-navigation arrow buttons ────────────────────────
                        // Always visible, centred on the left and right edges.
                        if (displayTotalPages > 1) {
                            val arrowAlpha = if ((baseBitmap ?: fallbackBitmap) != null) 0.55f else 0f
                            // 50% smaller when in split-pane view so they don't crowd the shared space.
                            val arrowBtnSize = if (compactArrows) 36.dp else 72.dp
                            val arrowIconSize = if (compactArrows) 18.dp else 36.dp
                            // Previous-page arrow (left)
                            IconButton(
                                onClick = { stepPage(-1) },
                                enabled = displayPage > 1,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 4.dp)
                                    .size(arrowBtnSize)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = arrowAlpha),
                                        shape = CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Previous page",
                                    modifier = Modifier.size(arrowIconSize),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (displayPage > 1) 0.9f else 0.35f)
                                )
                            }
                            // Next-page arrow (right)
                            IconButton(
                                onClick = { stepPage(1) },
                                enabled = displayPage < displayTotalPages,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 4.dp)
                                    .size(arrowBtnSize)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = arrowAlpha),
                                        shape = CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Next page",
                                    modifier = Modifier.size(arrowIconSize),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (displayPage < displayTotalPages) 0.9f else 0.35f)
                                )
                            }
                        }
                        when (renderState) {
                            is PdfRenderUiState.Error -> {
                                val message = (renderState as PdfRenderUiState.Error).message
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(10.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                            is PdfRenderUiState.Ready, is PdfRenderUiState.Loading -> Unit
                        }

                        if (showNavigationButtons) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                                tonalElevation = 3.dp,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    androidx.compose.material3.IconButton(
                                        onClick = { stepPage(-1) },
                                        enabled = displayTotalPages > 0 && displayPage > 1,
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Previous",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    androidx.compose.material3.IconButton(
                                        onClick = {
                                            if (onOpenSheetNavigator != null) {
                                                onOpenSheetNavigator()
                                            } else {
                                                showToc = true
                                            }
                                        },
                                        enabled = displayTotalPages > 0,
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.UnfoldMore,
                                            contentDescription = "Sheet list",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Text(
                                        "$displayPage/$displayTotalPages",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    androidx.compose.material3.IconButton(
                                        onClick = { stepPage(1) },
                                        enabled = displayTotalPages > 0 && displayPage < displayTotalPages,
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Next",
                                            modifier = Modifier.size(20.dp)
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

@Composable
private fun ZoomablePdfImage(
    baseBitmap: Bitmap?,
    detailBitmap: Bitmap?,
    pageKey: Int,
    onViewportStateChanged: (PdfViewportState) -> Unit,
    onInteractionChanged: (Boolean) -> Unit,
    pageAspectRatio: Float?,
    onGutterTapStep: ((Int) -> Unit)?,
    onSingleTap: (() -> Unit)? = null,
    allowStylusGestures: Boolean = true,
    allowFingerGestures: Boolean = true,
    modifier: Modifier = Modifier
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(pageKey) { mutableFloatStateOf(1f) }
    var panX by remember(pageKey) { mutableFloatStateOf(0f) }
    var panY by remember(pageKey) { mutableFloatStateOf(0f) }
    val minZoom = 1f
    val maxZoom = 14f
    val tapSlopPx = LocalViewConfiguration.current.touchSlop
    val gutterPanTolerancePx = 24f

    fun clampPan(targetZoom: Float, x: Float, y: Float): Pair<Float, Float> {
        if (viewSize == IntSize.Zero || targetZoom <= minZoom) return 0f to 0f
        val vw = viewSize.width.toFloat().coerceAtLeast(1f)
        val vh = viewSize.height.toFloat().coerceAtLeast(1f)
        val aspect = pageAspectRatio ?: (baseBitmap?.let {
            it.width.toFloat().coerceAtLeast(1f) / it.height.toFloat().coerceAtLeast(1f)
        } ?: 1f)
        val fitWidth = minOf(vw, vh * aspect)
        val fitHeight = minOf(vh, vw / aspect)
        val scaledWidth = fitWidth * targetZoom
        val scaledHeight = fitHeight * targetZoom
        val maxPanX = ((scaledWidth - vw) / 2f).coerceAtLeast(0f)
        val maxPanY = ((scaledHeight - vh) / 2f).coerceAtLeast(0f)
        return x.coerceIn(-maxPanX, maxPanX) to y.coerceIn(-maxPanY, maxPanY)
    }

    fun emitViewport() {
        onViewportStateChanged(PdfViewportState(zoom = zoom, panX = panX, panY = panY, viewSize = viewSize))
    }

    LaunchedEffect(pageKey, viewSize) {
        val (clampedX, clampedY) = clampPan(zoom, panX, panY)
        panX = clampedX
        panY = clampedY
        emitViewport()
    }

    Box(
        modifier = modifier
            .onSizeChanged {
                if (viewSize != it) {
                    android.util.Log.d("ReferencePdfPane", "ZoomablePdfImage size changed to: ${it.width}x${it.height}")
                    viewSize = it
                    emitViewport()
                }
            }
            .then(
                if (allowStylusGestures || allowFingerGestures) {
                    Modifier.pointerInput(pageKey, allowStylusGestures, allowFingerGestures) {
                        awaitEachGesture {
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            val isStylusGesture =
                                firstDown.type == androidx.compose.ui.input.pointer.PointerType.Stylus ||
                                    firstDown.type == androidx.compose.ui.input.pointer.PointerType.Eraser
                            val shouldHandleGesture = if (isStylusGesture) allowStylusGestures else allowFingerGestures
                            if (!shouldHandleGesture) {
                                do {
                                    val blockedEvent = awaitPointerEvent()
                                } while (blockedEvent.changes.any { it.pressed })
                                return@awaitEachGesture
                            }
                            onInteractionChanged(true)
                            var pointerCountMax = 1
                            var maxMoveDistance = 0f
                            var hadTransformInput = false
                            do {
                                val event = awaitPointerEvent()
                                pointerCountMax = max(pointerCountMax, event.changes.count { it.pressed })
                                val tracked = event.changes.firstOrNull { it.id == firstDown.id }
                                    ?: event.changes.firstOrNull { it.pressed }
                                    ?: event.changes.firstOrNull()
                                if (tracked != null) {
                                    val dx = tracked.position.x - firstDown.position.x
                                    val dy = tracked.position.y - firstDown.position.y
                                    maxMoveDistance = max(maxMoveDistance, sqrt(dx * dx + dy * dy))
                                }
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (abs(zoomChange - 1f) > 0.001f || abs(panChange.x) > 0.5f || abs(panChange.y) > 0.5f) {
                                    hadTransformInput = true
                                }
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val next = computeZoomPan(
                                    zoom = zoom,
                                    panX = panX,
                                    panY = panY,
                                    zoomChange = zoomChange,
                                    panChange = panChange,
                                    centroid = centroid,
                                    viewWidth = viewSize.width,
                                    viewHeight = viewSize.height,
                                    minZoom = minZoom,
                                    maxZoom = maxZoom
                                )
                                val (clampedX, clampedY) = clampPan(next.zoom, next.panX, next.panY)
                                zoom = next.zoom
                                panX = clampedX
                                panY = clampedY
                                emitViewport()
                            } while (event.changes.any { it.pressed })
                            onInteractionChanged(false)
                            emitViewport()

                            val isSingleTap = pointerCountMax == 1 &&
                                !hadTransformInput &&
                                maxMoveDistance <= tapSlopPx
                            var gutterHandled = false
                            if (isSingleTap && onGutterTapStep != null && isFitStateForSideGutterNavigation(
                                    zoom = zoom,
                                    panX = panX,
                                    panY = panY,
                                    panTolerancePx = gutterPanTolerancePx
                                )
                            ) {
                                when (
                                    classifySideGutterTap(
                                        tapX = firstDown.position.x,
                                        viewSize = viewSize,
                                        pageAspectRatio = pageAspectRatio
                                    )
                                ) {
                                    SideGutterTapRegion.LEFT -> { onGutterTapStep(-1); gutterHandled = true }
                                    SideGutterTapRegion.RIGHT -> { onGutterTapStep(1); gutterHandled = true }
                                    SideGutterTapRegion.NONE -> Unit
                                }
                            }
                            if (isSingleTap && !gutterHandled) {
                                onSingleTap?.invoke()
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        if (baseBitmap != null) {
            Image(
                bitmap = baseBitmap.asImageBitmap(),
                contentDescription = "Reference page",
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
            if (detailBitmap != null) {
                Image(
                    bitmap = detailBitmap.asImageBitmap(),
                    contentDescription = "Reference page detail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
    }
}

internal fun classifySideGutterTap(
    tapX: Float,
    viewSize: IntSize,
    pageAspectRatio: Float?
): SideGutterTapRegion {
    if (viewSize.width <= 0 || viewSize.height <= 0) return SideGutterTapRegion.NONE
    val aspect = pageAspectRatio?.takeIf { it > 0f } ?: return SideGutterTapRegion.NONE
    val fitted = fittedPageBounds(viewSize, aspect) ?: return SideGutterTapRegion.NONE
    if (fitted.hasNoSideGutters) return SideGutterTapRegion.NONE
    return when {
        tapX < fitted.left -> SideGutterTapRegion.LEFT
        tapX > fitted.right -> SideGutterTapRegion.RIGHT
        else -> SideGutterTapRegion.NONE
    }
}

internal fun isFitStateForSideGutterNavigation(
    zoom: Float,
    panX: Float,
    panY: Float,
    panTolerancePx: Float
): Boolean {
    return zoom <= 1.02f &&
        abs(panX) <= panTolerancePx &&
        abs(panY) <= panTolerancePx
}

private data class FittedPageBounds(
    val left: Float,
    val right: Float,
    val hasNoSideGutters: Boolean
)

private fun fittedPageBounds(viewSize: IntSize, aspect: Float): FittedPageBounds? {
    if (viewSize.width <= 0 || viewSize.height <= 0 || aspect <= 0f) return null
    val vw = viewSize.width.toFloat().coerceAtLeast(1f)
    val vh = viewSize.height.toFloat().coerceAtLeast(1f)
    val fitWidth = minOf(vw, vh * aspect)
    val sideGutter = ((vw - fitWidth) / 2f).coerceAtLeast(0f)
    return FittedPageBounds(
        left = sideGutter,
        right = sideGutter + fitWidth,
        hasNoSideGutters = sideGutter <= 0.5f
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ReferenceTocSheet(
    pageCount: Int,
    currentPage: Int,
    loadedCount: Int,
    tocThumbCache: Map<Int, Bitmap?>,
    onSelectPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val pages = remember(pageCount) { if (pageCount <= 0) emptyList() else (1..pageCount).toList() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        ImmersiveDialogDecor()
        Column(modifier = Modifier.fillMaxWidth().height(620.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sheet Navigator", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Loading thumbnails $loadedCount/$pageCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pages, key = { it }) { page ->
                    val selected = page == currentPage
                    val thumb = tocThumbCache[page]
                    Surface(
                        tonalElevation = if (selected) 3.dp else 1.dp,
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = { onSelectPage(page) }, onLongClick = { onSelectPage(page) })
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 120.dp, height = 90.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = "Page $page",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Sheet $page",
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

internal data class ZoomPanResult(
    val zoom: Float,
    val panX: Float,
    val panY: Float
)

/**
 * Computes the next zoom + pan from a transform gesture frame, compensating for
 * graphicsLayer's center-anchored scaling so the pinch centroid stays under the
 * fingers instead of the zoom appearing to originate from the view's center.
 *
 * Guards against an unspecified centroid: [calculateCentroid] returns
 * [Offset.Unspecified] (NaN, NaN) on the terminal frame of a gesture once all
 * pointers have lifted. Without the guard, `anchor * (1f - appliedZoomChange)`
 * evaluates to `NaN * 0f == NaN`, poisoning the pan offsets and crashing later
 * in `roundToInt()` ("Cannot round NaN value"). When there is no valid centroid
 * there is no anchor to compensate around, so the anchor contribution is zero.
 */
internal fun computeZoomPan(
    zoom: Float,
    panX: Float,
    panY: Float,
    zoomChange: Float,
    panChange: Offset,
    centroid: Offset,
    viewWidth: Int,
    viewHeight: Int,
    minZoom: Float,
    maxZoom: Float
): ZoomPanResult {
    val nextZoom = (zoom * zoomChange).coerceIn(minZoom, maxZoom)
    val appliedZoomChange = if (zoom == 0f) 1f else nextZoom / zoom
    val anchorX = if (centroid.isSpecified) centroid.x - viewWidth / 2f else 0f
    val anchorY = if (centroid.isSpecified) centroid.y - viewHeight / 2f else 0f
    val nextPanX = panX * appliedZoomChange + panChange.x + anchorX * (1f - appliedZoomChange)
    val nextPanY = panY * appliedZoomChange + panChange.y + anchorY * (1f - appliedZoomChange)
    return ZoomPanResult(zoom = nextZoom, panX = nextPanX, panY = nextPanY)
}

private data class QuantizedViewportState(
    val zoomX100: Int,
    val panX: Int,
    val panY: Int,
    val width: Int,
    val height: Int
)

private fun PdfViewportState.quantized(): QuantizedViewportState {
    return QuantizedViewportState(
        zoomX100 = (zoom * 100f).roundToInt(),
        panX = panX.roundToInt(),
        panY = panY.roundToInt(),
        width = viewSize.width,
        height = viewSize.height
    )
}

private fun trimTocCache(
    cache: MutableMap<Int, Bitmap?>,
    lruOrder: MutableList<Int>,
    maxEntries: Int
) {
    while (cache.size > maxEntries && lruOrder.isNotEmpty()) {
        val oldest = lruOrder.removeAt(0)
        cache.remove(oldest)
    }
}

private fun buildReferenceTocLoadOrder(pageCount: Int, currentPage: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val clamped = currentPage.coerceIn(1, pageCount)
    val ordered = ArrayList<Int>(pageCount)
    ordered += clamped
    var offset = 1
    while (ordered.size < pageCount) {
        val left = clamped - offset
        if (left >= 1) ordered += left
        val right = clamped + offset
        if (right <= pageCount) ordered += right
        offset++
    }
    return ordered
}
