package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class IdleActivityTrackerTest {

    private val config = IdlePowerSaveConfig(
        enabled = true,
        idleTimeoutSeconds = 5,
        syncthingPauseTimeoutSeconds = 30
    )

    @Test
    fun `stays active before idle timeout`() {
        assertEquals(IdlePhase.ACTIVE, computeIdlePhase(elapsedMs = 4_000L, config = config))
    }

    @Test
    fun `dims exactly at idle timeout`() {
        assertEquals(IdlePhase.DIMMED, computeIdlePhase(elapsedMs = 5_000L, config = config))
    }

    @Test
    fun `stays dimmed before syncthing pause timeout`() {
        assertEquals(IdlePhase.DIMMED, computeIdlePhase(elapsedMs = 29_000L, config = config))
    }

    @Test
    fun `pauses syncthing exactly at pause timeout`() {
        assertEquals(IdlePhase.SYNC_PAUSED, computeIdlePhase(elapsedMs = 30_000L, config = config))
    }

    @Test
    fun `disabled config always stays active regardless of elapsed time`() {
        val disabled = config.copy(enabled = false)
        assertEquals(IdlePhase.ACTIVE, computeIdlePhase(elapsedMs = 999_999L, config = disabled))
    }

    @Test
    fun `persisted idle timeouts are clamped to the safe minimum`() {
        val sanitized = sanitizeIdlePowerSaveConfig(
            IdlePowerSaveConfig(
                enabled = true,
                idleTimeoutSeconds = 0,
                syncthingPauseTimeoutSeconds = -1
            )
        )

        assertEquals(5, sanitized.idleTimeoutSeconds)
        assertEquals(5, sanitized.syncthingPauseTimeoutSeconds)
    }
}
