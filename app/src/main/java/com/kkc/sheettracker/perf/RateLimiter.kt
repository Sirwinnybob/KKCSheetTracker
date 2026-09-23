package com.kkc.sheettracker.perf

/**
 * Allows at most [maxPerWindow] events per key per [windowMs]. Keeps a misbehaving screen from
 * flooding the shared log folder with hundreds of near-identical entries.
 */
class RateLimiter(private val maxPerWindow: Int, private val windowMs: Long = 60 * 60_000L) {
    private val stamps = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun tryAcquire(key: String, nowMs: Long): Boolean {
        val q = stamps.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && nowMs - q.first() >= windowMs) q.removeFirst()
        if (q.size >= maxPerWindow) return false
        q.addLast(nowMs)
        return true
    }
}
