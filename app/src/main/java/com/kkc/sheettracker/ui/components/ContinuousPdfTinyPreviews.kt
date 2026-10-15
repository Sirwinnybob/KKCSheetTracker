package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Width of the whole-document placeholder previews; ~100 KB each as RGB_565. */
internal const val TINY_PREVIEW_WIDTH_PX = 200

/** Placeholder previews must not crowd out the visible pages' own decodes on open. */
internal const val TINY_PREVIEW_PREFETCH_START_DELAY_MS = 600L

internal fun tinyPreviewKey(filePath: String?, pdfFilename: String, sourcePage: Int, preferDarkMode: Boolean): String =
    "${filePath ?: pdfFilename}#$sourcePage#dark=$preferDarkMode"

/**
 * Display pages 1..[totalPages] ordered by distance from [startPage], nearest first, alternating
 * forward/backward, so the pages around where the user opened the file get previews first.
 */
internal fun tinyPreviewPrefetchOrder(startPage: Int, totalPages: Int): List<Int> {
    if (totalPages <= 0) return emptyList()
    val start = startPage.coerceIn(1, totalPages)
    val order = ArrayList<Int>(totalPages)
    order += start
    for (distance in 1 until totalPages) {
        if (start + distance <= totalPages) order += start + distance
        if (start - distance >= 1) order += start - distance
    }
    return order
}

/**
 * Byte-bounded cache of tiny page previews for one open document. Reads subscribe the caller to
 * [version], so a page that is already composed swaps its blank slot for the preview the moment the
 * background prefetch delivers it.
 */
internal class TinyPdfPreviewCache(maxBytes: Int = 24 * 1024 * 1024) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private var version by mutableIntStateOf(0)

    operator fun get(key: String): Bitmap? {
        version // subscribe
        return cache.get(key)?.takeUnless { it.isRecycled }
    }

    fun contains(key: String): Boolean = cache.get(key)?.isRecycled == false

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
        version++
    }

    fun clear() {
        cache.evictAll()
    }
}
