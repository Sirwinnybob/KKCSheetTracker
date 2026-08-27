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
 *
 * ### Threading
 * `fallbackLoader` performs blocking I/O in production (typically
 * `DeliveryScheduleRepository.fetchSchedule()`, which reads a Syncthing-replicated JSON file from
 * disk and documents "Call on Dispatchers.IO"). The loader-only constructor invokes
 * `fallbackLoader` synchronously, while the explicit `initialSchedule` constructor seam avoids
 * that eager read for UI-owned construction. [refreshFallback] always invokes the loader
 * synchronously on its caller's thread, so callers must invoke it off the main thread (for
 * example from a `Dispatchers.IO`-scoped coroutine).
 *
 * ### Concurrency
 * [schedule] and [liveConnected] are arbitrated by two concurrent update sources: a live
 * WebSocket client calling [applyLive]/[setLiveConnected] from a background IO-dispatcher
 * coroutine, and a periodic file-fallback refresh ([refreshFallback]) called from an I/O
 * coroutine. Every mutation that touches both fields is guarded by a single lock so a
 * [refreshFallback] call can never clobber a schedule that a concurrent [applyLive] has already
 * delivered while still reporting `liveConnected == true`.
 */
class DeliveryScheduleStateStore(
    initialSchedule: DeliverySchedule? = null,
    private val fallbackLoader: () -> DeliverySchedule
) {
    /** Guards every combined read/write of [_schedule] and [liveConnected] so they change atomically together. */
    private val lock = Any()

    /** Monotonic mutation token used to reject fallback data read before a newer UI/live update. */
    private var mutationVersion = 0L

    private val _schedule = MutableStateFlow(initialSchedule ?: fallbackLoader())
    val schedule: StateFlow<DeliverySchedule> = _schedule.asStateFlow()

    @Volatile
    var liveConnected: Boolean = false
        private set

    /** Delivers a live schedule payload from the WebSocket and marks the connection as live. */
    fun applyLive(schedule: DeliverySchedule) {
        synchronized(lock) {
            _schedule.value = schedule
            liveConnected = true
            mutationVersion += 1
        }
    }

    /** Pure connection-state signal. Does not itself deliver a schedule payload. */
    fun setLiveConnected(value: Boolean) {
        synchronized(lock) {
            liveConnected = value
            mutationVersion += 1
        }
        if (!value) {
            refreshFallback()
        }
    }

    /**
     * Marks the live stream disconnected without reading the fallback file. Callers that are on
     * the main thread (for example, a Compose effect's disposal callback) can use this to publish
     * the state transition immediately, then schedule [refreshFallback] on an I/O dispatcher.
     */
    fun markLiveDisconnected() {
        synchronized(lock) {
            liveConnected = false
            mutationVersion += 1
        }
    }

    /**
     * Reloads the file fallback. No-ops while a live schedule is authoritative.
     *
     * Calls the (blocking) `fallbackLoader` synchronously on the calling thread — see the class
     * kdoc "Threading" section. Must be invoked off the main thread.
     *
     * The stale-data check is re-validated atomically at commit time, so a fallback load that was
     * already in flight when a concurrent [applyLive] lands will not overwrite the live schedule.
     */
    fun refreshFallback() {
        val loadVersion = synchronized(lock) {
            if (liveConnected) return
            mutationVersion
        }
        val loaded = fallbackLoader()
        synchronized(lock) {
            if (liveConnected || mutationVersion != loadVersion) return
            _schedule.value = loaded
            mutationVersion += 1
        }
    }

    /**
     * Optimistic UI update right after a successful direct HTTP admin edit.
     * Replaces the schedule without touching connection status.
     */
    fun applyImmediate(schedule: DeliverySchedule) {
        synchronized(lock) {
            _schedule.value = schedule
            mutationVersion += 1
        }
    }
}
