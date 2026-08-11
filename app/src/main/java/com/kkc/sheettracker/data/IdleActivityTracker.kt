package com.kkc.sheettracker.data

import android.os.SystemClock
import kotlin.jvm.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class IdlePhase { ACTIVE, DIMMED, SYNC_PAUSED }

internal fun computeIdlePhase(elapsedMs: Long, config: IdlePowerSaveConfig): IdlePhase {
    if (!config.enabled) return IdlePhase.ACTIVE
    val elapsedSeconds = elapsedMs / 1000L
    return when {
        elapsedSeconds >= config.syncthingPauseTimeoutSeconds -> IdlePhase.SYNC_PAUSED
        elapsedSeconds >= config.idleTimeoutSeconds -> IdlePhase.DIMMED
        else -> IdlePhase.ACTIVE
    }
}

/**
 * Ticks once a second comparing elapsed time since [reset] against [config]'s two thresholds.
 * [reset] itself also recomputes synchronously so a touch reverts phase to ACTIVE immediately,
 * without waiting for the next tick.
 */
class IdleActivityTracker(
    private val config: StateFlow<IdlePowerSaveConfig>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val tickMs: Long = 1_000L
) {
    @Volatile private var lastInteractionAtMs = nowMs()
    private var tickJob: Job? = null

    private val _phase = MutableStateFlow(IdlePhase.ACTIVE)
    val phase: StateFlow<IdlePhase> = _phase.asStateFlow()

    private val _pollIntervalOverrideMs = MutableStateFlow<Long?>(null)
    val pollIntervalOverrideMs: StateFlow<Long?> = _pollIntervalOverrideMs.asStateFlow()

    fun reset() {
        lastInteractionAtMs = nowMs()
        recompute()
    }

    fun start() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                recompute()
                delay(tickMs)
            }
        }
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun recompute() {
        val currentConfig = config.value
        val newPhase = computeIdlePhase(nowMs() - lastInteractionAtMs, currentConfig)
        _phase.value = newPhase
        _pollIntervalOverrideMs.value = if (newPhase == IdlePhase.ACTIVE) {
            null
        } else {
            currentConfig.idleTimeoutSeconds * 1000L
        }
    }
}
