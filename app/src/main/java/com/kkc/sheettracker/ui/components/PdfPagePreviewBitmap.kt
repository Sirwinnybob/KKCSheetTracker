package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.PreferredColorConfig
import com.kkc.sheettracker.data.loadPdfPagePreview
import com.kkc.sheettracker.data.loadPdfThumbBundle
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bound native decode allocations to the display size. Never enlarge the worker's raster. */
internal fun pdfPreviewDecodeSize(
    sourceWidth: Int,
    sourceHeight: Int,
    bounds: IntSize,
    qualityScale: Float = 1f
): IntSize? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || bounds.width <= 0 || bounds.height <= 0 ||
        !qualityScale.isFinite() || qualityScale <= 0f) return null
    var scale = minOf(1.0, bounds.width.toDouble() * qualityScale / sourceWidth,
        bounds.height.toDouble() * qualityScale / sourceHeight)
    val maxPixels = 8_000_000L
    val area = sourceWidth.toDouble() * sourceHeight * scale * scale
    if (area > maxPixels) scale *= sqrt(maxPixels / area)
    val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (sourceHeight * scale).roundToInt().coerceIn(1, (maxPixels / width).toInt().coerceAtLeast(1))
    return IntSize(width, height)
}

internal fun loadPdfPagePreviewBitmap(
    pdf: File,
    pageIndex: Int,
    bounds: IntSize,
    qualityScale: Float = 1f,
    colorConfig: PreferredColorConfig = PreferredColorConfig.RGBA_8888
): Bitmap? = loadPdfPagePreview(pdf, pageIndex) { image ->
    val bytes = image.readBytes()
    val source = JxlCoder.getSize(bytes) ?: return@loadPdfPagePreview null
    val size = pdfPreviewDecodeSize(source.width, source.height, bounds, qualityScale)
        ?: return@loadPdfPagePreview null
    if (size.width == source.width && size.height == source.height) {
        JxlCoder.decode(bytes, preferredColorConfig = colorConfig)
    } else {
        JxlCoder.decodeSampled(bytes, size.width, size.height, preferredColorConfig = colorConfig)
    }
}

/** Aspect ratios keyed by image identity (path, size, mtime) so a replaced JXL is re-read. */
private val aspectRatioCache = android.util.LruCache<String, Float>(512)

private const val JXL_HEADER_BYTES = 4096

/** Image dimensions live in the first bytes of a JXL. Reading the whole 2560px file just to get
 * its size (once per page, on the layout path) was a large share of the per-page cost. */
private fun jxlAspectRatio(image: File): Float? {
    fun ratio(bytes: ByteArray): Float? {
        val size = try { JxlCoder.getSize(bytes) } catch (_: Exception) { null } ?: return null
        return if (size.width <= 0 || size.height <= 0) null else size.width.toFloat() / size.height
    }
    val head = image.inputStream().use { input ->
        val buffer = ByteArray(JXL_HEADER_BYTES)
        val read = input.read(buffer)
        if (read <= 0) return null
        if (read == buffer.size) buffer else buffer.copyOf(read)
    }
    return ratio(head) ?: ratio(image.readBytes())
}

/** Every page of the worker's thumbnail zip as RGB_565 bitmaps (zero-based page index), or null
 * when the PDF has no valid, current zip. */
internal fun loadPdfThumbBundleBitmaps(pdf: File): Map<Int, Bitmap>? =
    loadPdfThumbBundle(pdf) { bytes ->
        val options = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

internal fun pdfPagePreviewAspectRatio(pdf: File, pageIndex: Int): Float? =
    loadPdfPagePreview(pdf, pageIndex) { image ->
        val key = "${image.absolutePath}|${image.length()}|${image.lastModified()}"
        aspectRatioCache.get(key) ?: jxlAspectRatio(image)?.also { aspectRatioCache.put(key, it) }
    }
