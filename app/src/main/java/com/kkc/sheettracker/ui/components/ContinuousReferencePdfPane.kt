package com.kkc.sheettracker.ui.components

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
 */
internal class PdfEngineCache(
    private val maxOpen: Int = 3
) {
    private val engines = linkedMapOf<String, PdfRenderEngine>()
    private var order = listOf<String>()

    fun get(file: java.io.File): PdfRenderEngine {
        val key = file.absolutePath
        order = lruTouch(order, key)
        engines[key]?.let { return it }
        val engine = PdfRenderEngine(file)
        engines[key] = engine
        return engine
    }

    suspend fun trim() {
        val evicted = lruEvictionCandidates(order, maxOpen)
        evicted.forEach { key ->
            engines.remove(key)?.close()
        }
        order = order.filterNot { it in evicted }
    }

    suspend fun closeAll() {
        engines.values.forEach { it.close() }
        engines.clear()
        order = emptyList()
    }
}
