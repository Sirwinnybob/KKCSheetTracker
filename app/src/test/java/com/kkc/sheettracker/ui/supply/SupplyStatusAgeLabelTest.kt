package com.kkc.sheettracker.ui.supply

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupplyStatusAgeLabelTest {
    private val now = Instant.parse("2026-09-23T18:00:00Z")

    @Test
    fun `blank or malformed statusAt yields no label`() {
        assertNull(supplyStatusAgeLabel("", now))
        assertNull(supplyStatusAgeLabel("not a date", now))
    }

    @Test
    fun `under one day reads today`() {
        assertEquals("today", supplyStatusAgeLabel("2026-09-23T02:00:00Z", now))
        assertEquals("today", supplyStatusAgeLabel("2026-09-22T18:00:01Z", now))
    }

    @Test
    fun `whole days are floored`() {
        assertEquals("1d", supplyStatusAgeLabel("2026-09-22T18:00:00Z", now))
        assertEquals("5d", supplyStatusAgeLabel("2026-09-18T09:30:00.123Z", now))
    }

    @Test
    fun `future timestamp from clock skew reads today`() {
        assertEquals("today", supplyStatusAgeLabel("2026-09-24T18:00:00Z", now))
    }
}
