package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DeliverySchedule
import java.util.concurrent.atomic.AtomicLong

/**
 * Guards asynchronous delivery-schedule lifecycle work against a stale client/effect instance.
 *
 * A source token identifies one [DeliveryScheduleLiveClient] binding. A lifecycle token identifies
 * one ON_START interval for that source. Stopping, disposing, or replacing either invalidates the
 * token captured by an in-flight coroutine, so a slow fallback read cannot start an old client.
 */
internal class DeliveryScheduleLifecycleGate {
    private val lock = Any()
    private var nextToken = 0L
    private var currentSourceToken = 0L
    private var currentLifecycleToken = 0L
    private var currentCleanupToken = 0L

    /** Claims the source identity for a newly installed lifecycle effect. */
    fun bindSource(): Long = synchronized(lock) {
        val token = ++nextToken
        currentSourceToken = token
        currentLifecycleToken = 0L
        currentCleanupToken = 0L
        token
    }

    /** Claims an ON_START interval for [sourceToken], or returns 0 if it is already stale. */
    fun begin(sourceToken: Long): Long = synchronized(lock) {
        if (sourceToken != currentSourceToken) return 0L
        val token = ++nextToken
        currentLifecycleToken = token
        currentCleanupToken = 0L
        token
    }

    /** Invalidates any in-flight ON_START work for [sourceToken]. */
    fun stop(sourceToken: Long): Long = synchronized(lock) {
        if (sourceToken != currentSourceToken) return 0L
        val token = ++nextToken
        currentLifecycleToken = token
        currentCleanupToken = 0L
        token
    }

    /** Invalidates [sourceToken] and returns a token for disposal-only cleanup work. */
    fun dispose(sourceToken: Long): Long = synchronized(lock) {
        if (sourceToken != currentSourceToken) return 0L
        val cleanupToken = ++nextToken
        currentSourceToken = cleanupToken
        currentLifecycleToken = 0L
        currentCleanupToken = cleanupToken
        cleanupToken
    }

    /** True only while the source and lifecycle interval are both still current. */
    fun isCurrent(sourceToken: Long, lifecycleToken: Long): Boolean = synchronized(lock) {
        sourceToken == currentSourceToken &&
            lifecycleToken != 0L &&
            lifecycleToken == currentLifecycleToken
    }

    /** Runs [action] only if a callback still belongs to the current client/effect source. */
    fun runIfSourceCurrent(sourceToken: Long, action: () -> Unit): Boolean = synchronized(lock) {
        if (sourceToken == 0L || sourceToken != currentSourceToken) return false
        action()
        true
    }

    /**
     * Runs [action] while holding the source/lifecycle guard, preventing disposal or stop from
     * interleaving between the final check and a client start.
     */
    fun runIfCurrent(sourceToken: Long, lifecycleToken: Long, action: () -> Unit): Boolean =
        synchronized(lock) {
            if (sourceToken != currentSourceToken ||
                lifecycleToken == 0L ||
                lifecycleToken != currentLifecycleToken
            ) {
                return false
            }
            action()
            true
        }

    /** True only while disposal cleanup has not been superseded by a replacement source. */
    fun isCleanupCurrent(cleanupToken: Long): Boolean = synchronized(lock) {
        cleanupToken != 0L && cleanupToken == currentCleanupToken
    }
}

/**
 * Identity captured by one DeliveryScheduleLiveClient instance. A replacement client gets a new
 * binding, so a callback that snapshots this token cannot be mistaken for the replacement source.
 */
internal class DeliveryScheduleClientBinding(
    private val lifecycleGate: DeliveryScheduleLifecycleGate
) {
    private val boundSourceToken = AtomicLong(0L)

    fun bind(sourceToken: Long) {
        require(sourceToken != 0L) { "source token must be non-zero" }
        check(boundSourceToken.compareAndSet(0L, sourceToken)) {
            "delivery client binding already claimed"
        }
    }

    /**
     * Produces the exact schedule callback supplied to this binding's live client. The lifecycle
     * gate and the store write run under the same lock, so source replacement cannot interleave
     * after the current-source check but before the shared store is mutated.
     */
    fun scheduleCallback(store: DeliveryScheduleStateStore): (DeliverySchedule) -> Unit = { schedule ->
        lifecycleGate.runIfSourceCurrent(boundSourceToken.get()) {
            store.applyLive(schedule)
        }
    }

    /** Produces the exact connection callback supplied to this binding's live client. */
    fun connectionCallback(store: DeliveryScheduleStateStore): (Boolean) -> Unit = { connected ->
        lifecycleGate.runIfSourceCurrent(boundSourceToken.get()) {
            store.setLiveConnected(connected)
        }
    }
}
