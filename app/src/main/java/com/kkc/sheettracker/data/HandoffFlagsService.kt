package com.kkc.sheettracker.data

import android.util.Log
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.HandoffTruckListEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Instant

/**
 * Process-wide "handoff flags may have changed" tick. Bumped by TrackerChangeMonitor (HT rewrote
 * handoff.json / a sidecar changed), by this tablet's own taps and by a REST `skipped` reply.
 * Screens re-read flags when it changes; reading is one small file, so no per-job precision.
 */
object HandoffFlagsChangeSignal {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()
    fun bump() { _version.update { it + 1 } }
}

/** Text to show the user for a refused tap; null when the tap was recorded. */
fun HandoffFlagWriteResult.userMessage(): String? = when (this) {
    HandoffFlagWriteResult.Written -> null
    HandoffFlagWriteResult.JobMissing -> "This job is no longer in Ready Jobs"
    HandoffFlagWriteResult.StorageUnavailable -> "Couldn't save on this tablet. Try again."
    HandoffFlagWriteResult.Invalid -> "Couldn't save this change."
}

data class HandoffFlagsSnapshot(
    val stored: Map<HandoffFlagRef, HandoffItemFlags> = emptyMap(),
    val pending: Map<HandoffFlagRef, PendingTruckFlag> = emptyMap(),
    /** Null when HT wasn't reachable: Auto placement is then unknown. */
    val truckList: Map<HandoffFlagRef, HandoffTruckListEntry>? = null
)

data class HandoffTruckDisplay(
    val label: String,
    /** The pinned value (pending wins over stored); null = Auto. */
    val selected: HandoffTruck?,
    val isAuto: Boolean,
    val isPending: Boolean,
    /** Null = unknown (Auto, offline): never draw a ticked or unticked "Ship loose" box. */
    val shipsLoose: Boolean?,
    val countLabel: String?
)

fun handoffTruckDisplay(ref: HandoffFlagRef, snapshot: HandoffFlagsSnapshot): HandoffTruckDisplay {
    val pending = snapshot.pending[ref]
    val stored = snapshot.stored[ref]
    val selected = if (pending != null) pending.truck else stored?.truck
    val countLabel = stored?.count?.label
    if (selected != null) {
        return HandoffTruckDisplay(selected.label, selected, false, pending != null, selected == HandoffTruck.LOOSE, countLabel)
    }
    val entry = snapshot.truckList?.get(ref)
    // With no pin, effective == auto. Use autoTruck: the cached list's effectiveTruck is stale
    // right after a tap back to Auto (it still reflects the old pin until the next fetch).
    val autoTruck = entry?.autoTruck
    val (label, shipsLoose) = when {
        entry == null -> "Auto" to null
        entry.excluded -> "Auto · Excluded by office" to false
        autoTruck != null -> "Auto · ${autoTruck.label}" to (autoTruck == HandoffTruck.LOOSE)
        else -> "Auto" to null
    }
    return HandoffTruckDisplay(label, null, true, pending != null, shipsLoose, countLabel)
}

/**
 * One tap = one `requestedAt`, used for BOTH writes:
 * 1. sidecar append (always; the offline path and HT's 30 s pickup),
 * 2. best-effort `PUT …/handoff/flags` in [restScope] (the tap never waits for it).
 */
class HandoffFlagsService(
    private val baseDir: File,
    tabletId: String,
    private val clientProvider: suspend () -> HandoffFlagsClient?,
    private val restScope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC()
) {
    private val requests = HandoffFlagRequestStore(baseDir, tabletId)

    /**
     * Stored flags (handoff.json) plus this tablet's pending sidecar requests. File reads run on
     * [Dispatchers.IO], so it is safe to call from a UI-scoped coroutine.
     */
    suspend fun loadLocal(jobFolderName: String): HandoffFlagsSnapshot = withContext(Dispatchers.IO) {
        HandoffFlagsSnapshot(
            stored = loadHandoffItemFlags(baseDir, jobFolderName),
            pending = requests.pendingTruckFlags(jobFolderName, Instant.now(clock))
        )
    }

    /** Auto placements from HT; null when HT is unreachable or [clientProvider] fails. */
    suspend fun fetchTruckList(jobFolderName: String): Map<HandoffFlagRef, HandoffTruckListEntry>? =
        try {
            clientProvider()?.fetchTruckList(jobFolderName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Truck list fetch failed for $jobFolderName", e)
            null
        }

    /**
     * Records the tap. The sidecar append (mutex, file read/parse, atomic write) runs on
     * [Dispatchers.IO]; the REST call is launched in [restScope] and never awaited, and only after
     * the sidecar write succeeded ([HandoffFlagWriteResult.Written]). Any other result means
     * nothing was recorded anywhere: no PUT, no change signal, and no job folder is ever created.
     */
    suspend fun setTruck(jobFolderName: String, ref: HandoffFlagRef, truck: HandoffTruck?): HandoffFlagWriteResult {
        val requestedAt = Instant.now(clock).toString()
        val result = withContext(Dispatchers.IO) {
            requests.appendTruckRequest(jobFolderName, ref, truck, requestedAt)
        }
        when (result) {
            HandoffFlagWriteResult.Written -> Unit
            HandoffFlagWriteResult.Invalid -> {
                Log.w(TAG, "Truck flag tap rejected as invalid for ${ref.key} in $jobFolderName — tablet bug")
                return result
            }
            HandoffFlagWriteResult.JobMissing -> {
                Log.w(TAG, "Truck flag tap not recorded for ${ref.key}: $jobFolderName is no longer in Ready Jobs")
                return result
            }
            HandoffFlagWriteResult.StorageUnavailable -> {
                Log.w(TAG, "Truck flag tap not recorded for ${ref.key} in $jobFolderName (sidecar unreadable or unwritable)")
                return result
            }
        }
        HandoffFlagsChangeSignal.bump()
        restScope.launch {
            try {
                when (val result = clientProvider()?.putTruck(jobFolderName, ref, truck, requestedAt)) {
                    HandoffFlagPutResult.Skipped -> HandoffFlagsChangeSignal.bump()
                    is HandoffFlagPutResult.Failed -> if (result.status == 400 || result.status == 422) {
                        Log.w(TAG, "HT rejected flag body (${result.status}) for ${ref.key} in $jobFolderName — tablet bug")
                    }
                    is HandoffFlagPutResult.Applied, null -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The sidecar already carries the request; HT picks it up within 30 s.
                Log.w(TAG, "Flag PUT failed for ${ref.key} in $jobFolderName", e)
            }
        }
        return result
    }

    private companion object { const val TAG = "HandoffFlags" }
}
