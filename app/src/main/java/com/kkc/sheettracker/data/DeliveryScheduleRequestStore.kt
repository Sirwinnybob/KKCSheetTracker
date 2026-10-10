package com.kkc.sheettracker.data

import android.util.Log
import com.google.gson.GsonBuilder
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.isWeekdaySlotKey
import com.kkc.sheettracker.data.models.parseDatedSlotKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

data class DeliveryScheduleSlotEdit(
    val slot: String,
    val jobs: List<DeliveryJob>
)

data class DeliveryScheduleEditRequest(
    val tabletId: String,
    val requestedAt: String,
    val resetAll: Boolean = false,
    val slotEdits: List<DeliveryScheduleSlotEdit> = emptyList()
)

data class DeliveryQueueResult(val queued: Boolean, val droppedJobs: List<DeliveryJob>)

/** The offline fallback for delivery edits; [DeliveryScheduleRequestStore] is the production one. */
interface DeliveryScheduleQueue {
    suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String): DeliveryQueueResult
    suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?)
}

/**
 * Queues tablet-authored schedule edits for the Hours Tracker backend to apply to the master
 * `.metadata/delivery_schedule.json`. This mirrors the production/job-board request pattern:
 * Android writes one request file per tablet, the server consumes it, then Android re-reads
 * master state.
 *
 * CROSS-PROGRAM (see METADATA_AUDIT.md M-04): request files are named
 * `delivery_schedule_request.<tabletId>.json` (one per tablet) at the Ready Jobs root. HT polls
 * every 15 s, applies files oldest-first by `requestedAt`, deletes applied files and moves
 * malformed ones to `<name>.rejected` (never touched here). Validation below mirrors HT's
 * rejection rules so a bad edit never poisons the whole file.
 *
 * Read-modify-write is serialized per file with an in-process Mutex (fixes METADATA_AUDIT.md L-06),
 * written atomically, and a file that can't be parsed is copied to `.unreadable-<millis>` before a
 * new one is started (never silently overwritten).
 *
 * An existing file is only ever replaced when it was read successfully and either parsed or was
 * backed up first. If it can't be read (I/O error) or the backup copy fails, nothing is written:
 * [queueSlotEdit] returns `DeliveryQueueResult(queued = false, droppedJobs = jobs)` and
 * [queueReset] logs and returns without writing (it has no result to report; callers keep the
 * original queued file untouched and may retry).
 */
class DeliveryScheduleRequestStore(
    private val baseDir: File,
    private val clockMillis: () -> Long = System::currentTimeMillis
) : DeliveryScheduleQueue {

    private sealed interface Existing {
        object Missing : Existing
        data class Parsed(val request: DeliveryScheduleEditRequest) : Existing
        /** Could not read the file, or could not back up an unparseable one: do not write. */
        object Blocked : Existing
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun requestFile(tabletId: String) = File(baseDir, "delivery_schedule_request.$tabletId.json")

    override suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String): DeliveryQueueResult {
        val normalizedSlot = slot.trim().lowercase()
        val dated = parseDatedSlotKey(normalizedSlot) != null
        if (!dated && !isWeekdaySlotKey(normalizedSlot)) return DeliveryQueueResult(false, jobs)
        val valid = jobs.filter { job ->
            job.jobNumber.isNotBlank() && job.description.isNotBlank() && (!dated || job.folderName.isNotBlank())
        }
        // Safety cap only: the real limit is per day (six), enforced by the editor and HT.
        val kept = valid.take(MAX_DELIVERY_JOBS_PER_DAY)
        val dropped = jobs.filterNot { it in kept }
        return lockFor(tabletId).withLock {
            val existing = when (val e = readExisting(tabletId)) {
                Existing.Blocked -> return@withLock DeliveryQueueResult(false, jobs)
                Existing.Missing -> null
                is Existing.Parsed -> e.request
            }
            writeRequest(
                tabletId,
                DeliveryScheduleEditRequest(
                    tabletId = tabletId,
                    requestedAt = Instant.now().toString(),
                    resetAll = existing?.resetAll ?: false,
                    slotEdits = existing?.slotEdits.orEmpty().filterNot { it.slot == normalizedSlot } +
                        DeliveryScheduleSlotEdit(slot = normalizedSlot, jobs = kept)
                )
            )
            DeliveryQueueResult(true, dropped)
        }
    }

    override suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?) {
        lockFor(tabletId).withLock {
            val existing = when (val e = readExisting(tabletId)) {
                Existing.Blocked -> {
                    Log.w(TAG, "queueReset skipped: ${requestFile(tabletId).name} could not be read or backed up")
                    return@withLock
                }
                Existing.Missing -> null
                is Existing.Parsed -> e.request
            }
            // HT applies resetAll first, then slotEdits. Edits for slots the reset clears would
            // re-apply stale intent, so drop them; edits for other weeks / weekends survive.
            val survivors = existing?.slotEdits.orEmpty().filter { edit ->
                val date = parseDatedSlotKey(edit.slot)?.date ?: return@filter false
                mirroredWeekOf != null && (date < mirroredWeekOf || date > mirroredWeekOf.plusDays(4))
            }
            writeRequest(
                tabletId,
                DeliveryScheduleEditRequest(
                    tabletId = tabletId,
                    requestedAt = Instant.now().toString(),
                    resetAll = true,
                    slotEdits = survivors
                )
            )
        }
    }

    /** This tablet's queued request, or null when none is waiting (or it can't be read). */
    fun readPending(tabletId: String): DeliveryScheduleEditRequest? {
        val file = requestFile(tabletId)
        if (!file.isFile) return null
        return runCatching { gson.fromJson(file.readText(), DeliveryScheduleEditRequest::class.java) }
            .getOrNull()
            ?.takeIf { it.slotEdits != null }
    }

    /**
     * [Existing.Missing] when there is no file. A read I/O error is [Existing.Blocked] (no backup,
     * file left alone). A parse failure is backed up: [Existing.Missing] once the copy succeeded
     * (safe to replace), [Existing.Blocked] if the copy failed.
     */
    private fun readExisting(tabletId: String): Existing {
        val file = requestFile(tabletId)
        if (!file.exists()) return Existing.Missing
        val text = try {
            file.readText()
        } catch (e: IOException) {
            Log.w(TAG, "Could not read ${file.name}; leaving it untouched", e)
            return Existing.Blocked
        }
        val parsed = runCatching { gson.fromJson(text, DeliveryScheduleEditRequest::class.java) }
            .getOrNull()
            ?.takeIf { it.slotEdits != null }
        if (parsed != null) return Existing.Parsed(parsed)
        val backup = File(file.parentFile, "${file.name}.unreadable-${clockMillis()}")
        try {
            file.copyTo(backup, overwrite = false)
        } catch (e: Exception) {
            Log.w(TAG, "Could not back up unreadable ${file.name}; leaving it untouched", e)
            return Existing.Blocked
        }
        Log.w(TAG, "Unreadable ${file.name}; kept a copy as ${backup.name} and started a new request")
        return Existing.Missing
    }

    private fun writeRequest(tabletId: String, payload: DeliveryScheduleEditRequest) {
        atomicWriteFile(requestFile(tabletId), gson.toJson(payload))
    }

    private fun lockFor(tabletId: String): Mutex =
        locks.getOrPut(requestFile(tabletId).absolutePath) { Mutex() }

    private companion object {
        const val TAG = "DeliveryRequestStore"
        /** Process-wide so two store instances for the same file still serialize. */
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
