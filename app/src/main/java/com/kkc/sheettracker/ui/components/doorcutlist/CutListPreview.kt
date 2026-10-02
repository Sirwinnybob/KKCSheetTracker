package com.kkc.sheettracker.ui.components.doorcutlist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.components.kkcCardDepth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "DoorPanelCutList"
private const val PREVIEW_DEBOUNCE_MS = 450L
// ~900x1165 ARGB_8888 = ~4.2 MB per page, so a full preview is ~25 MB at most.
private const val PREVIEW_MAX_WIDTH_PX = 900
private const val PREVIEW_MAX_PAGES = 6

/** Rendered preview bitmaps (first [PREVIEW_MAX_PAGES] only) plus the PDF's real page count. */
internal data class CutListPreviewPages(val bitmaps: List<Bitmap>, val totalPages: Int)

/**
 * Live preview of the printed cut list: renders the real PDF to bitmaps (debounced) so what you
 * see is exactly what prints. The page stays white in dark mode on purpose -- it is the printout.
 */
@Composable
internal fun CutListPreview(model: DoorPanelCutListModel, modifier: Modifier = Modifier) {
    if (!model.canPrint) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Select at least one material and room to preview.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val context = LocalContext.current
    var preview by remember { mutableStateOf<CutListPreviewPages?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var firstRender by remember { mutableStateOf(true) }

    BoxWithConstraints(modifier = modifier) {
        val targetWidthPx = minOf(constraints.maxWidth, PREVIEW_MAX_WIDTH_PX)

        LaunchedEffect(model, targetWidthPx) {
            // Show progress immediately, even while debouncing, so a stale preview is never
            // presented as current.
            rendering = true
            if (!firstRender) delay(PREVIEW_DEBOUNCE_MS)
            firstRender = false
            // Drop the old bitmaps only once the render really starts, so a quick toggle during
            // the debounce keeps the pane filled and old + new sets are never held together.
            preview = null
            failed = false
            try {
                preview = withContext(Dispatchers.IO) { renderCutListPreviewPages(context, model, targetWidthPx) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cut list preview failed", e)
                failed = true
            }
            rendering = false
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (rendering) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
            } else {
                Box(modifier = Modifier.height(3.dp))
            }
            val shown = preview
            if (shown == null || shown.bitmaps.isEmpty()) {
                if (failed) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Preview unavailable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(shown.bitmaps) { index, bitmap ->
                        val label = "Page ${index + 1} of ${shown.totalPages}"
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                color = Color.White,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .kkcCardDepth(MaterialTheme.shapes.small)
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = label,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    if (shown.totalPages > shown.bitmaps.size) {
                        item {
                            Text(
                                text = "Preview shows the first ${shown.bitmaps.size} of ${shown.totalPages} pages — Print includes all pages.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the cut list PDF for [model] into bitmaps [targetWidthPx] wide (page height proportional),
 * at most [PREVIEW_MAX_PAGES] of them. Cooperatively cancellable: checks the coroutine's Job after
 * writing the PDF and before every page allocation. Out-of-memory is reported as a regular
 * exception (after recycling this call's bitmaps) so the UI shows "Preview unavailable".
 * Uses a unique temp file under cacheDir/print-preview so a cancelled render can never clash with
 * the next one; never touches cacheDir/print, which prepareCutListPrintFile wipes.
 */
internal suspend fun renderCutListPreviewPages(
    context: Context,
    model: DoorPanelCutListModel,
    targetWidthPx: Int,
): CutListPreviewPages {
    val dir = File(context.cacheDir, "print-preview").apply { mkdirs() }
    val file = File.createTempFile("preview", ".pdf", dir)
    var fd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    val result = ArrayList<Bitmap>(PREVIEW_MAX_PAGES)
    try {
        writeDoorPanelCutListPdf(model, file)
        currentCoroutineContext().ensureActive()
        fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(fd)
        val width = targetWidthPx.coerceAtLeast(1)
        val totalPages = renderer.pageCount
        val count = minOf(totalPages, PREVIEW_MAX_PAGES)
        for (i in 0 until count) {
            currentCoroutineContext().ensureActive()
            var page: PdfRenderer.Page? = null
            try {
                page = renderer.openPage(i)
                val height = (page.height.toFloat() * width / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                result.add(bitmap)
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                runCatching { page?.close() }
            }
        }
        return CutListPreviewPages(result, totalPages)
    } catch (e: OutOfMemoryError) {
        // Nothing from this call has been handed out yet, so recycling is safe.
        result.forEach { it.recycle() }
        throw IllegalStateException("Out of memory rendering cut list preview", e)
    } catch (e: CancellationException) {
        result.forEach { it.recycle() }
        throw e
    } finally {
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        file.delete()
    }
}
