package com.kkc.sheettracker.clock

import com.kkc.sheettracker.data.ClockInSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockInForegroundServiceTest {
    @Test
    fun `formatElapsed uses hh mm ss`() {
        assertEquals("00:00:00", ClockInForegroundService.formatElapsed(0L))
        assertEquals("00:00:59", ClockInForegroundService.formatElapsed(59_000L))
        assertEquals("00:01:00", ClockInForegroundService.formatElapsed(60_000L))
        assertEquals("01:01:01", ClockInForegroundService.formatElapsed(3_661_000L))
    }

    @Test
    fun `inactive foreground service start promotes before stopping`() {
        val actions = ClockInForegroundService.foregroundActionsForSnapshot(
            snapshot = ClockInSnapshot(isActive = false),
            isForegroundStarted = false
        )

        assertEquals(
            listOf(
                ClockInForegroundService.ForegroundAction.StartForeground,
                ClockInForegroundService.ForegroundAction.StopSelf
            ),
            actions
        )
    }
}
