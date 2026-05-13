package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
        if (!pdfFile.exists() || viewport.viewSize == IntSize.Zero) return null
        val localRenderer = ensureRendererLocked()
        if (pageIndex !in 0 until localRenderer.pageCount) return null
        var page: PdfRenderer.Page? = null
        try {
            page = localRenderer.openPage(pageIndex)
            val activePage = page ?: return null
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
            activePage.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            runCatching { page?.close() }
        }
    }

    suspend fun pageAspectRatio(pageIndex: Int): Float? = mutex.withLock {
        if (!pdfFile.exists()) return null
        val localRenderer = ensureRendererLocked()
        if (pageIndex !in 0 until localRenderer.pageCount) return null
        var page: PdfRenderer.Page? = null
        return try {
            page = localRenderer.openPage(pageIndex)
            val activePage = page ?: return null
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
        if (!pdfFile.exists() || viewSize == IntSize.Zero) return null
        val localRenderer = ensureRendererLocked()
        if (pageIndex !in 0 until localRenderer.pageCount) return null
        var page: PdfRenderer.Page? = null
        try {
            page = localRenderer.openPage(pageIndex)
            val activePage = page ?: return null
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
            activePage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally {
            runCatching { page?.close() }
        }
    }

    suspend fun renderThumbnail(pageIndex: Int, maxWidth: Int = 340): Bitmap? = mutex.withLock {
        if (!pdfFile.exists()) return null
        val localRenderer = ensureRendererLocked()
        if (pageIndex !in 0 until localRenderer.pageCount) return null
        var page: PdfRenderer.Page? = null
        try {
            page = localRenderer.openPage(pageIndex)
            val activePage = page ?: return null
            val scale = maxWidth.toFloat() / activePage.width.toFloat().coerceAtLeast(1f)
            val width = (activePage.width * scale).toInt().coerceAtLeast(1)
            val height = (activePage.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            activePage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            runCatching { page?.close() }
        }
    }

    fun close() {
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        renderer = null
        fd = null
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
    onOpenSheetNavigator: (() -> Unit)? = null
) {
    val engine = remember(pdfFile?.absolutePath) { pdfFile?.takeIf { it.exists() }?.let { PdfRenderEngine(it) } }
    DisposableEffect(engine) {
        onDispose { engine?.close() }
    }

    var totalPages by remember(engine) { mutableIntStateOf(0) }
    var viewportState by remember(engine) { mutableStateOf(PdfViewportState()) }
    var isInteracting by remember(engine) { mutableStateOf(false) }
    var pageAspectRatio by remember(engine, currentPage) { mutableStateOf<Float?>(null) }
    var renderState by remember(engine, currentPage) { mutableStateOf<PdfRenderUiState>(PdfRenderUiState.Loading) }
    val matteColorArgb = MaterialTheme.colorScheme.surface.toArgb()
    var baseBitmap by remember(engine, currentPage) { mutableStateOf<Bitmap?>(null) }
    var detailBitmap by remember(engine, currentPage) { mutableStateOf<Bitmap?>(null) }
    var detailForViewport by remember(engine, currentPage) { mutableStateOf<QuantizedViewportState?>(null) }
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

    LaunchedEffect(tocRequestToken, totalPages, onOpenSheetNavigator) {
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

    LaunchedEffect(viewportState) {
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
        val renderedBase = withContext(Dispatchers.IO) {
            engine.renderBasePage(
                pageIndex = (currentPage - 1).coerceAtLeast(0),
                viewSize = viewSize,
                matteColorArgb = matteColorArgb
            )
        }
        if (renderedBase != null) {
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
            if (onStepPage != null) onStepPage(-1)
            else onCurrentPageChange((currentPage - 1).coerceAtLeast(1))
        } else if (delta > 0 && displayPage < displayTotalPages) {
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
            tonalElevation = 1.dp
        ) {
            when {
                pdfFile == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(missingText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                totalPages <= 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(unreadableText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Box(Modifier.fillMaxSize()) {
                        val shouldShowDetail = detailBitmap != null &&
                            detailForViewport == viewportState.quantized() &&
                            !isInteracting

                        ZoomablePdfImage(
                            baseBitmap = baseBitmap,
                            detailBitmap = if (shouldShowDetail) detailBitmap else null,
                            pageKey = currentPage,
                            onViewportStateChanged = { viewportState = it },
                            onInteractionChanged = { isInteracting = it },
                            pageAspectRatio = pageAspectRatio,
                            onGutterTapStep = stepPage,
                            modifier = Modifier.fillMaxSize()
                        )
                        when (renderState) {
                            PdfRenderUiState.Loading -> {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(10.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                        Text("Rendering", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
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
                            is PdfRenderUiState.Ready -> Unit
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
            .pointerInput(pageKey) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
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
                        val nextZoom = (zoom * zoomChange).coerceIn(minZoom, maxZoom)
                        val nextPanX = panX + panChange.x
                        val nextPanY = panY + panChange.y
                        val (clampedX, clampedY) = clampPan(nextZoom, nextPanX, nextPanY)
                        zoom = nextZoom
                        panX = clampedX
                        panY = clampedY
                        emitViewport()
                    } while (event.changes.any { it.pressed })
                    onInteractionChanged(false)
                    emitViewport()

                    val isSingleTap = pointerCountMax == 1 &&
                        !hadTransformInput &&
                        maxMoveDistance <= tapSlopPx
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
                            SideGutterTapRegion.LEFT -> onGutterTapStep(-1)
                            SideGutterTapRegion.RIGHT -> onGutterTapStep(1)
                            SideGutterTapRegion.NONE -> Unit
                        }
                    }
                }
            }
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
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
