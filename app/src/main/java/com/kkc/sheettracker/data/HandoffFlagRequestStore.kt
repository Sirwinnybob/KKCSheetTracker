package com.kkc.sheettracker.data

import android.util.Log
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffTruck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingTruckFlag(val truck: HandoffTruck?, val requestedAt: Instant)

/** Outcome of [HandoffFlagRequestStore.appendTruckRequest]. */
sealed interface HandoffFlagWriteResult {
    object Written : HandoffFlagWriteResult
    /** Bad source/id/requestedAt — a tablet bug. */
    object Invalid : HandoffFlagWriteResult
    /** `<job>/.metadata` is gone (renamed/archived): nothing written, no folder created. */
    object JobMissing : HandoffFlagWriteResult
    /** Sidecar unreadable, backup failed, or write IOException. */
    object StorageUnavailable : HandoffFlagWriteResult
    /** The store was built read-only (archive screens): nothing is read or written. */
    object ReadOnly : HandoffFlagWriteResult
}

/**
 * Appends tablet flag changes to `<job>/.metadata/admin/handoff_flags_request.<tabletId>.json`
 * (HT METADATA_CONTRACT.md §2.12). HT applies the file every 30 s / before packet generation /
 * when the office opens the job, and deletes it only if its bytes didn't change while it read.
 *
 * - Only the touched flag is written; Auto is an explicit JSON null (org.json NULL, not Gson).
 * - One invalid request makes HT quarantine the WHOLE file as `.rejected`, so validate first.
 * - Earlier entries (even ones this build doesn't understand) are preserved verbatim.
 * - org.json writes UTF-8 without a BOM via `writeText`.
 * - A file that cannot be read, or that is unparseable and cannot be backed up first, is never
 *   overwritten: the append is refused ([HandoffFlagWriteResult.StorageUnavailable]) so no earlier
 *   request is lost.
 * - Never creates a job folder: nothing is written unless `<job>/.metadata` already exists
 *   ([HandoffFlagWriteResult.JobMissing] otherwise).
 */
class HandoffFlagRequestStore(
    private val baseDir: File,
    private val tabletId: String,
    internal val clockMillis: () -> Long = System::currentTimeMillis,
    private val readOnly: Boolean = false
) {

    /** File I/O runs on [Dispatchers.IO] inside this call, so callers need not switch context. */
    suspend fun appendTruckRequest(
        jobFolderName: String,
        ref: HandoffFlagRef,
        truck: HandoffTruck?,
        requestedAt: String
    ): HandoffFlagWriteResult {
        if (readOnly) return HandoffFlagWriteResult.ReadOnly
        if (ref.source !in HandoffFlagRef.SOURCES || ref.id.isBlank()) return HandoffFlagWriteResult.Invalid
        if (runCatching { Instant.parse(requestedAt) }.isFailure) return HandoffFlagWriteResult.Invalid
        return withContext(Dispatchers.IO) { appendLocked(jobFolderName, ref, truck, requestedAt) }
    }

    private suspend fun appendLocked(
        jobFolderName: String,
        ref: HandoffFlagRef,
        truck: HandoffTruck?,
        requestedAt: String
    ): HandoffFlagWriteResult {
        // Before the lock and before any read/mkdirs: atomicWriteFile creates missing parents, so a
        // renamed/archived job would otherwise get a near-empty folder recreated by a stale tap.
        // Not reported to JobFolderGuardEvents: the tap shows its own "no longer in Ready Jobs" snackbar.
        if (!isLiveJobFolder(baseDir, jobFolderName)) return HandoffFlagWriteResult.JobMissing
        val file = requestFile(jobFolderName)
        return lockFor(file).withLock {
            val root = readOrStartNew(file) ?: return@withLock HandoffFlagWriteResult.StorageUnavailable
            val request = JSONObject()
                .put("requestId", UUID.randomUUID().toString())
                .put("requestedAt", requestedAt)
                .put("source", ref.source)
                .put("id", ref.id)
                .put("truck", truck?.wire ?: JSONObject.NULL)
            root.getJSONArray("requests").put(request)
            try {
                atomicWriteFile(file, root.toString())
                HandoffFlagWriteResult.Written
            } catch (e: IOException) {
                Log.w(TAG, "Could not write ${file.name}", e)
                HandoffFlagWriteResult.StorageUnavailable
            }
        }
    }

    /**
     * Last truck request per item in this tablet's file that HT has not yet applied.
     *
     * A request stays pending for as long as it sits in the sidecar (HT deletes the file once it
     * has applied it; offline, that can take hours) UNLESS
     * - [truckSetAt] (`handoff.json` `itemFlagsSetAt[key].truck`) is at or after its `requestedAt`
     *   (HT applied it, or the office has overridden it since), or
     * - it is older than the [backstop] (a stuck file must not show "pending" forever).
     *
     * A `requestedAt` more than [CLOCK_SKEW] in the future (tablet clock ahead of HT) is anchored
     * to the first time it is observed, so polling cannot extend its lifetime or undo an override.
     * Ties on raw `requestedAt` go to the later entry in the file.
     */
    suspend fun pendingTruckFlags(
        jobFolderName: String,
        now: Instant,
        truckSetAt: Map<HandoffFlagRef, Instant> = emptyMap(),
        backstop: Duration = PENDING_BACKSTOP
    ): Map<HandoffFlagRef, PendingTruckFlag> = withContext(Dispatchers.IO) {
        val file = requestFile(jobFolderName)
        lockFor(file).withLock { pendingTruckFlagsLocked(file, jobFolderName, now, truckSetAt, backstop) }
    }

    private fun pendingTruckFlagsLocked(
        file: File,
        jobFolderName: String,
        now: Instant,
        truckSetAt: Map<HandoffFlagRef, Instant>,
        backstop: Duration
    ): Map<HandoffFlagRef, PendingTruckFlag> {
        if (!file.isFile) return emptyMap()
        val requests = runCatching { JSONObject(file.readText()).getJSONArray("requests") }.getOrNull() ?: return emptyMap()
        val clockFile = File(file.parentFile, file.name + PENDING_CLOCK_SUFFIX)
        val clockRead = readClockAnchors(clockFile)
        val anchors = clockRead.anchors.toMutableMap()
        val liveIdentities = HashSet<String>()
        val newAnchors = HashSet<String>()

        for (i in 0 until requests.length()) {
            val obj = requests.optJSONObject(i) ?: continue
            if (!obj.has("truck")) continue
            val raw = runCatching { Instant.parse(obj.getString("requestedAt")) }.getOrNull() ?: continue
            val requestId = obj.optString("requestId").takeIf { it.isNotBlank() } ?: continue
            if (!obj.isNull("truck") && HandoffTruck.fromWire(obj.optString("truck")) == null) continue
            val identity = requestIdentity(requestId, raw)
            liveIdentities += identity
            if (identity !in anchors && raw.isAfter(now.plus(CLOCK_SKEW))) {
                anchors[identity] = now
                newAnchors += identity
            }
        }

        val pruned = anchors.keys.retainAll(liveIdentities)
        val changed = newAnchors.isNotEmpty() || pruned
        // Reads may happen from archive screens or after a job was renamed while this sidecar is
        // still on disk. Do not let atomicWriteFile recreate a missing job/admin folder.
        val canPersist = !readOnly && isLiveJobFolder(baseDir, jobFolderName) && clockRead.writable
        val anchorWriteSucceeded = if (changed && canPersist) {
            runCatching { writeClockAnchors(clockFile, anchors) }
                .onFailure { Log.w(TAG, "Could not persist clock anchors for ${file.name}; skipping newly skewed requests", it) }
                .isSuccess
        } else newAnchors.isEmpty()
        val unavailableAnchors = if (anchorWriteSucceeded) emptySet() else newAnchors

        val result = LinkedHashMap<HandoffFlagRef, PendingTruckFlag>()
        val orderKey = HashMap<HandoffFlagRef, Instant>()
        for (i in 0 until requests.length()) {
            val obj = requests.optJSONObject(i) ?: continue
            if (!obj.has("truck")) continue
            val raw = runCatching { Instant.parse(obj.getString("requestedAt")) }.getOrNull() ?: continue
            val requestId = obj.optString("requestId").takeIf { it.isNotBlank() } ?: continue
            val identity = requestIdentity(requestId, raw)
            if (identity in unavailableAnchors) continue
            val at = anchors[identity] ?: raw
            if (Duration.between(at, now) > backstop) continue
            val ref = HandoffFlagRef(obj.optString("source"), obj.optString("id"))
            val truck = if (obj.isNull("truck")) null else HandoffTruck.fromWire(obj.optString("truck")) ?: continue
            val previous = orderKey[ref]
            // Order by the stamped time so a skewed-ahead entry still sorts after earlier ones.
            if (previous == null || !raw.isBefore(previous)) {
                orderKey[ref] = raw
                result[ref] = PendingTruckFlag(truck, at)
            }
        }
        result.entries.removeAll { (ref, pending) ->
            val setAt = truckSetAt[ref]
            setAt != null && !setAt.isBefore(pending.requestedAt)
        }
        return result
    }

    private data class ClockAnchorRead(val anchors: Map<String, Instant>, val writable: Boolean)

    private fun readClockAnchors(file: File): ClockAnchorRead {
        if (!file.exists()) return ClockAnchorRead(emptyMap(), writable = true)
        return try {
            val root = JSONObject(file.readText())
            val saved = root.optJSONObject("anchors") ?: return ClockAnchorRead(emptyMap(), writable = false)
            val anchors = buildMap {
                val keys = saved.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    runCatching { Instant.parse(saved.getString(key)) }.getOrNull()?.let { put(key, it) }
                }
            }
            ClockAnchorRead(anchors, writable = true)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read clock anchors from ${file.name}; leaving them untouched", e)
            ClockAnchorRead(emptyMap(), writable = false)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable clock anchors in ${file.name}; leaving them untouched", e)
            ClockAnchorRead(emptyMap(), writable = false)
        }
    }

    private fun writeClockAnchors(file: File, anchors: Map<String, Instant>) {
        val entries = JSONObject()
        anchors.forEach { (identity, at) -> entries.put(identity, at.toString()) }
        atomicWriteFile(file, JSONObject().put("anchors", entries).toString())
    }

    private fun requestIdentity(requestId: String, raw: Instant): String = "$requestId\u0000${raw}"

    /**
     * The request document to append to, or null when the file must not be touched (it exists but
     * cannot be read, or is unparseable and a backup copy could not be made first).
     */
    private fun readOrStartNew(file: File): JSONObject? {
        if (file.exists()) {
            val text = try {
                file.readText()
            } catch (e: IOException) {
                Log.w(TAG, "Cannot read ${file.name}; leaving it untouched and skipping this request", e)
                return null
            }
            val parsed = runCatching { JSONObject(text) }.getOrNull()
                ?.takeIf { it.optJSONArray("requests") != null }
            if (parsed != null) return parsed
            val backup = File(file.parentFile, "${file.name}.unreadable-${clockMillis()}")
            try {
                file.copyTo(backup, overwrite = false)
            } catch (e: IOException) {
                Log.w(TAG, "Could not back up unreadable ${file.name}; leaving it untouched and skipping this request", e)
                return null
            }
            Log.w(TAG, "Unreadable ${file.name}; kept a copy as ${backup.name} and started a new file")
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("tabletId", tabletId)
            .put("requests", JSONArray())
    }

    private fun requestFile(jobFolderName: String) =
        File(baseDir, "$jobFolderName/.metadata/admin/handoff_flags_request.$tabletId.json")

    private fun lockFor(file: File): Mutex = locks.getOrPut(file.absolutePath) { Mutex() }

    companion object {
        private const val TAG = "HandoffFlagRequests"
        /** Hard upper bound on how long a queued request is shown as pending. */
        val PENDING_BACKSTOP: Duration = Duration.ofHours(24)
        /** A `requestedAt` further ahead than this is a tablet clock problem and gets an anchor. */
        val CLOCK_SKEW: Duration = Duration.ofMinutes(5)
        private const val PENDING_CLOCK_SUFFIX = ".pending-clock"
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
