package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockInBillingTest {
    private fun min(m: Double) = (m * 60_000L).toLong()

    @Test
    fun underSevenMinutesBillsNothing() {
        assertEquals(0.0, ClockInBilling.billedHours(0L), 0.0)
        assertEquals(0.0, ClockInBilling.billedHours(min(6.99)), 0.0)
    }

    @Test
    fun exactlySevenMinutesBillsOneIncrement() {
        assertEquals(0.25, ClockInBilling.billedHours(min(7.0)), 0.0)
    }

    @Test
    fun roundsUpToNextQuarterHour() {
        assertEquals(0.25, ClockInBilling.billedHours(min(15.0)), 0.0)
        assertEquals(0.50, ClockInBilling.billedHours(min(16.0)), 0.0)
        assertEquals(0.50, ClockInBilling.billedHours(min(23.0)), 0.0)
        assertEquals(1.00, ClockInBilling.billedHours(min(60.0)), 0.0)
        assertEquals(1.25, ClockInBilling.billedHours(min(60.0) + 1L), 0.0)
    }

    @Test
    fun clockOutPrefillNeverBelowOneIncrement() {
        assertEquals(0.25, ClockInBilling.clockOutPrefillHours(min(2.0)), 0.0)
        assertEquals(0.50, ClockInBilling.clockOutPrefillHours(min(16.0)), 0.0)
    }

    @Test
    fun nextChangeCountsToThresholdThenPastEachBoundary() {
        assertEquals(min(6.0), ClockInBilling.msUntilNextChange(min(1.0)))
        assertEquals(min(5.0) + 1L, ClockInBilling.msUntilNextChange(min(10.0)))
        assertEquals(1L, ClockInBilling.msUntilNextChange(min(15.0)))
        assertEquals(min(15.0), ClockInBilling.msUntilNextChange(min(15.0) + 1L))
    }

    @Test
    fun formatsElapsedWithoutPaddedHour() {
        assertEquals("0:01:03", ClockInBilling.formatElapsed(63_000L))
        assertEquals("12:00:00", ClockInBilling.formatElapsed(12L * 3_600_000L))
    }

    @Test
    fun formatsHoursWithTwoDecimals() {
        assertEquals("0.50 hr", ClockInBilling.formatHours(0.5))
    }
}
