package com.kkc.sheettracker.ui.components

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    suspend fun trim() = mutex.withLock {
        evictLocked()
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
