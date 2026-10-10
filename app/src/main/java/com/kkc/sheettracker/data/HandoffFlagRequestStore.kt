package com.kkc.sheettracker.data

import android.util.Log
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffTruck
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    internal val clockMillis: () -> Long = System::currentTimeMillis
) {

    suspend fun appendTruckRequest(
        jobFolderName: String,
        ref: HandoffFlagRef,
        truck: HandoffTruck?,
        requestedAt: String
    ): HandoffFlagWriteResult {
        if (ref.source !in HandoffFlagRef.SOURCES || ref.id.isBlank()) return HandoffFlagWriteResult.Invalid
        if (runCatching { Instant.parse(requestedAt) }.isFailure) return HandoffFlagWriteResult.Invalid
        // Before the lock and before any read/mkdirs: atomicWriteFile creates missing parents, so a
        // renamed/archived job would otherwise get a near-empty folder recreated by a stale tap.
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

    /** Last truck request per item still in this tablet's file and younger than [ttl]. */
    fun pendingTruckFlags(jobFolderName: String, now: Instant, ttl: Duration = PENDING_TTL): Map<HandoffFlagRef, PendingTruckFlag> {
        val file = requestFile(jobFolderName)
        if (!file.isFile) return emptyMap()
        val requests = runCatching { JSONObject(file.readText()).getJSONArray("requests") }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<HandoffFlagRef, PendingTruckFlag>()
        for (i in 0 until requests.length()) {
            val obj = requests.optJSONObject(i) ?: continue
            if (!obj.has("truck")) continue
            val at = runCatching { Instant.parse(obj.getString("requestedAt")) }.getOrNull() ?: continue
            if (Duration.between(at, now) > ttl) continue
            val ref = HandoffFlagRef(obj.optString("source"), obj.optString("id"))
            val truck = if (obj.isNull("truck")) null else HandoffTruck.fromWire(obj.optString("truck")) ?: continue
            val previous = result[ref]
            if (previous == null || !at.isBefore(previous.requestedAt)) result[ref] = PendingTruckFlag(truck, at)
        }
        return result
    }

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
        val PENDING_TTL: Duration = Duration.ofSeconds(120)
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
