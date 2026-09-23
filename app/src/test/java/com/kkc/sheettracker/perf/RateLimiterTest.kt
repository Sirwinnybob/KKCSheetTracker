package com.kkc.sheettracker.perf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun allowsUpToTheLimitThenBlocksWithinTheWindow() {
        val r = RateLimiter(maxPerWindow = 3, windowMs = 60_000)
        assertTrue(r.tryAcquire("a", 0))
        assertTrue(r.tryAcquire("a", 1_000))
        assertTrue(r.tryAcquire("a", 2_000))
        assertFalse(r.tryAcquire("a", 3_000))
    }

    @Test
    fun windowSlidesSoOldEntriesFreeUpCapacity() {
        val r = RateLimiter(maxPerWindow = 2, windowMs = 60_000)
        r.tryAcquire("a", 0)
        r.tryAcquire("a", 10_000)
        assertFalse(r.tryAcquire("a", 30_000))
        assertTrue("first stamp aged out", r.tryAcquire("a", 60_000))
    }

    @Test
    fun keysAreIndependent() {
        val r = RateLimiter(maxPerWindow = 1, windowMs = 60_000)
        assertTrue(r.tryAcquire("cpu", 0))
        assertTrue(r.tryAcquire("idle", 0))
        assertFalse(r.tryAcquire("cpu", 1))
    }
}
