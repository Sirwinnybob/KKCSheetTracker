package com.kkc.sheettracker.data.cnc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val MACHINE_POLL_INTERVAL_MS = 5_000L
const val MACHINE_OFFLINE_GRACE_MS = 180_000L

sealed interface MachineStatus {
    data object Unavailable : MachineStatus
    data class Live(
        val loaded: LoadedProgram?,
        val lastFinished: FinishedProgram?,
        val asOf: String? = null,
        val reportDate: String? = null,
        val producing: Boolean? = null,
        val state: String? = null,
    ) : MachineStatus
}

/** In-memory, read-only machine status. Observers own polling leases, not separate loops. */
class MachineStatusStore(
    private val source: MachineStateSource,
    private val scope: CoroutineScope,
    private val elapsedRealtimeMs: () -> Long,
    private val offlineGraceMs: Long = MACHINE_OFFLINE_GRACE_MS,
    private val onAvailabilityChanged: (Boolean) -> Unit = {},
) {
    private val lock = Any()
    private val fetchMutex = Mutex()
    private val mutableStatus = MutableStateFlow<MachineStatus>(MachineStatus.Unavailable)
    val status: StateFlow<MachineStatus> = mutableStatus.asStateFlow()
    private var observers = 0
    private var generation = 0L
    private var lastSuccessMs: Long? = null
    private var pollJob: Job? = null
    private var expiryJob: Job? = null

    fun acquire(): AutoCloseable {
        synchronized(lock) {
            expireIfNeeded()
            if (observers++ == 0) {
                val expected = ++generation
                armExpiry(expected)
                pollJob = scope.launch { poll(expected) }
            }
        }
        var closed = false
        return AutoCloseable {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    if (--observers == 0) {
                        generation++
                        pollJob?.cancel()
                        expiryJob?.cancel()
                        pollJob = null
                        expiryJob = null
                    }
                }
            }
        }
    }

    private suspend fun poll(expected: Long) {
        while (currentCoroutineContext().isActive) {
            val started = elapsedRealtimeMs()
            val result = fetchMutex.withLock {
                currentCoroutineContext().ensureActive()
                try { source.fetch() } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Result.failure(e)
                }
            }
            synchronized(lock) {
                if (generation != expected || observers == 0) return
                result.getOrNull()?.let { snapshot ->
                    lastSuccessMs = elapsedRealtimeMs()
                    publish(MachineStatus.Live(snapshot.currentProgram,
                        snapshot.lastProgram.takeUnless { snapshot.haveReport == false },
                        snapshot.asOf, snapshot.reportDate, snapshot.producing, snapshot.state))
                    armExpiry(expected)
                }
                expireIfNeeded()
            }
            val elapsed = (elapsedRealtimeMs() - started).coerceAtLeast(0L)
            val slots = maxOf(1L, (elapsed + MACHINE_POLL_INTERVAL_MS - 1) / MACHINE_POLL_INTERVAL_MS)
            delay((slots * MACHINE_POLL_INTERVAL_MS - elapsed).coerceAtLeast(0L))
        }
    }

    /** Called under lock; independent of a stalled fetch. */
    private fun armExpiry(expected: Long) {
        expiryJob?.cancel()
        val success = lastSuccessMs ?: return
        expiryJob = scope.launch {
            delay((success + offlineGraceMs - elapsedRealtimeMs()).coerceAtLeast(0L))
            synchronized(lock) {
                if (generation == expected && observers > 0) expireIfNeeded()
            }
        }
    }

    private fun expireIfNeeded() {
        val success = lastSuccessMs ?: return
        if (elapsedRealtimeMs() - success >= offlineGraceMs) publish(MachineStatus.Unavailable)
    }

    private fun publish(next: MachineStatus) {
        val wasAvailable = mutableStatus.value is MachineStatus.Live
        mutableStatus.value = next
        val available = next is MachineStatus.Live
        if (wasAvailable != available) onAvailabilityChanged(available)
    }
}
