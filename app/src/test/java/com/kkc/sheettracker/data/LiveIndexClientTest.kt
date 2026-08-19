package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveIndexClientTest {

    @Test
    fun `backoff doubles from 1s and caps at 30s`() {
        assertEquals(1_000L, nextBackoffDelayMs(0))
        assertEquals(2_000L, nextBackoffDelayMs(1))
        assertEquals(4_000L, nextBackoffDelayMs(2))
        assertEquals(8_000L, nextBackoffDelayMs(3))
        assertEquals(16_000L, nextBackoffDelayMs(4))
        assertEquals(30_000L, nextBackoffDelayMs(5))
        assertEquals(30_000L, nextBackoffDelayMs(6))
        assertEquals(30_000L, nextBackoffDelayMs(100))
    }
}
