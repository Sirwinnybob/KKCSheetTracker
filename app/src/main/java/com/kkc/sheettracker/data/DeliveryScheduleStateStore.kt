package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DeliverySchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides which delivery schedule (live WebSocket push vs. Syncthing-replicated file fallback)
 * is currently authoritative for the tablet UI.
 *
 * `fallbackLoader` is injected as a lambda (rather than a `DeliveryScheduleRepository` instance)
 * so this store stays filesystem-free and unit-testable.
 */
class DeliveryScheduleStateStore(
    private val fallbackLoader: () -> DeliverySchedule
) {
    private val _schedule = MutableStateFlow(fallbackLoader())
    val schedule: StateFlow<DeliverySchedule> = _schedule.asStateFlow()

    var liveConnected: Boolean = false
        private set

    /** Delivers a live schedule payload from the WebSocket and marks the connection as live. */
    fun applyLive(schedule: DeliverySchedule) {
        _schedule.value = schedule
        liveConnected = true
    }

    /** Pure connection-state signal. Does not itself deliver a schedule payload. */
    fun setLiveConnected(value: Boolean) {
        liveConnected = value
        if (!value) {
            refreshFallback()
        }
    }

    /** Reloads the file fallback. No-ops while a live schedule is authoritative. */
    fun refreshFallback() {
        if (liveConnected) return
        _schedule.value = fallbackLoader()
    }

    /**
     * Optimistic UI update right after a successful direct HTTP admin edit.
     * Replaces the schedule without touching connection status.
     */
    fun applyImmediate(schedule: DeliverySchedule) {
        _schedule.value = schedule
    }
}
