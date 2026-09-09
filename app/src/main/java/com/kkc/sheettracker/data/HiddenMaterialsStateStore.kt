package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides which hidden-materials document (live WebSocket push vs. Syncthing-replicated file
 * fallback) is currently authoritative for one mode's tablet UI. Mirrors
 * DeliveryScheduleStateStore exactly -- see its kdoc for the full threading/concurrency
 * rationale, which applies identically here.
 *
 * Unlike DeliveryScheduleStateStore, there is no `applyImmediate` here: the hidden-materials
 * REST fast path's response carries no canonical document (just `{"applied": true}`), so a
 * successful call only means "don't fall back to the sidecar" -- the UI picks up the actual
 * change via the live push (which the backend sends synchronously, before the REST handler even
 * returns) or, if disconnected, the next [refreshFallback].
 */
class HiddenMaterialsStateStore(
    initialDocument: HiddenMaterialsDocument? = null,
    private val fallbackLoader: () -> HiddenMaterialsDocument
) {
    private val lock = Any()
    private var mutationVersion = 0L

    private val _document = MutableStateFlow(initialDocument ?: fallbackLoader())
    val document: StateFlow<HiddenMaterialsDocument> = _document.asStateFlow()

    @Volatile
    var liveConnected: Boolean = false
        private set

    /** Delivers a live document payload from the WebSocket and marks the connection as live. */
    fun applyLive(document: HiddenMaterialsDocument) {
        synchronized(lock) {
            _document.value = document
            liveConnected = true
            mutationVersion += 1
        }
    }

    /** Pure connection-state signal. Does not itself deliver a document payload. */
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
     * Marks the live stream disconnected without reading the fallback files. Callers that are on
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
     * Reloads the file fallback. No-ops while a live document is authoritative.
     *
     * Calls the (blocking) `fallbackLoader` synchronously on the calling thread -- must be
     * invoked off the main thread.
     *
     * The stale-data check is re-validated atomically at commit time, so a fallback load that was
     * already in flight when a concurrent [applyLive] lands will not overwrite the live document.
     */
    fun refreshFallback() {
        val loadVersion = synchronized(lock) {
            if (liveConnected) return
            mutationVersion
        }
        val loaded = fallbackLoader()
        synchronized(lock) {
            if (liveConnected || mutationVersion != loadVersion) return
            _document.value = loaded
            mutationVersion += 1
        }
    }
}
