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
import kotlinx.coroutines.launch
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
    fun bump() { _version.value = _version.value + 1 }
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
    val effectiveTruck = entry?.effectiveTruck
    val (label, shipsLoose) = when {
        entry == null -> "Auto" to null
        entry.excluded -> "Auto · Excluded by office" to false
        effectiveTruck != null -> "Auto · ${effectiveTruck.label}" to (effectiveTruck == HandoffTruck.LOOSE)
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

    fun loadLocal(jobFolderName: String): HandoffFlagsSnapshot = HandoffFlagsSnapshot(
        stored = loadHandoffItemFlags(baseDir, jobFolderName),
        pending = requests.pendingTruckFlags(jobFolderName, Instant.now(clock))
    )

    suspend fun fetchTruckList(jobFolderName: String): Map<HandoffFlagRef, HandoffTruckListEntry>? =
        clientProvider()?.fetchTruckList(jobFolderName)

    suspend fun setTruck(jobFolderName: String, ref: HandoffFlagRef, truck: HandoffTruck?): Boolean {
        val requestedAt = Instant.now(clock).toString()
        if (!requests.appendTruckRequest(jobFolderName, ref, truck, requestedAt)) return false
        HandoffFlagsChangeSignal.bump()
        restScope.launch {
            when (val result = clientProvider()?.putTruck(jobFolderName, ref, truck, requestedAt)) {
                HandoffFlagPutResult.Skipped -> HandoffFlagsChangeSignal.bump()
                is HandoffFlagPutResult.Failed -> if (result.status == 400 || result.status == 422) {
                    Log.w(TAG, "HT rejected flag body (${result.status}) for ${ref.key} in $jobFolderName — tablet bug")
                }
                is HandoffFlagPutResult.Applied, null -> Unit
            }
        }
        return true
    }

    private companion object { const val TAG = "HandoffFlags" }
}
