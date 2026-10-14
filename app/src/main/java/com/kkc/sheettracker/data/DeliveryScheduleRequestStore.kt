package com.kkc.sheettracker.data

import android.util.Log
import com.google.gson.GsonBuilder
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.isWeekdaySlotKey
import com.kkc.sheettracker.data.models.parseDatedSlotKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

/** Why [DeliveryScheduleQueue.queueSlotEdit] wrote nothing (see [DeliveryQueueResult.reason]). */
enum class DeliveryQueueFailure {
    /** The request file could not be read, backed up or written; the original is untouched. */
    Blocked,
    /** The slot key is neither a dated key nor a weekday key. */
    InvalidSlot,
    /** The dated slot is older than [DELIVERY_HISTORY_DAYS]; HT drops such slots. */
    TooOld,
    /** Every submitted job was invalid for a dated slot; writing the empty list would clear the slot on HT. */
    AllJobsInvalid
}

/**
 * [queued] is false when nothing was written, and [reason] says why. A queued edit may still list
 * [droppedJobs] (invalid, or over the cap); an unqueued one lists every submitted job there.
 */
data class DeliveryQueueResult(
    val queued: Boolean,
    val droppedJobs: List<DeliveryJob>,
    val reason: DeliveryQueueFailure? = null
)

/** The offline fallback for delivery edits; [DeliveryScheduleRequestStore] is the production one. */
interface DeliveryScheduleQueue {
    suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String): DeliveryQueueResult

    /** False when nothing was written (the request file is blocked); true once the reset is queued. */
    suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?): Boolean
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
 * new one is started (never silently overwritten); only the [MAX_UNREADABLE_BACKUPS] newest copies
 * per request file are kept.
 *
 * An existing file is only ever replaced when it was read successfully and either parsed or was
 * backed up first. If it can't be read (I/O error), the backup copy fails or the write fails,
 * nothing is replaced: [queueSlotEdit] returns `queued = false` with [DeliveryQueueFailure.Blocked]
 * and [queueReset] returns false; callers keep the original queued file and may retry.
 *
 * Every suspend entry point runs its file work on [Dispatchers.IO]. [today] is the shop calendar
 * date used to refuse dated slots older than [DELIVERY_HISTORY_DAYS] and to place a reset window
 * when the schedule has no `weekOf`.
 */
class DeliveryScheduleRequestStore(
    private val baseDir: File,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = { shopToday() }
) : DeliveryScheduleQueue {

    private sealed interface Existing {
        object Missing : Existing
        data class Parsed(val request: DeliveryScheduleEditRequest) : Existing
        /** Could not read the file, or could not back up an unparseable one: do not write. */
        object Blocked : Existing
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun requestFile(tabletId: String) = File(baseDir, "delivery_schedule_request.$tabletId.json")

    override suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String): DeliveryQueueResult =
        withContext(Dispatchers.IO) {
            val normalizedSlot = slot.trim().lowercase()
            val datedKey = parseDatedSlotKey(normalizedSlot)
            val dated = datedKey != null
            if (!dated && !isWeekdaySlotKey(normalizedSlot)) {
                return@withContext DeliveryQueueResult(false, jobs, DeliveryQueueFailure.InvalidSlot)
            }
            if (datedKey != null && datedKey.date < today().minusDays(DELIVERY_HISTORY_DAYS)) {
                return@withContext DeliveryQueueResult(false, jobs, DeliveryQueueFailure.TooOld)
            }
            // Dated keys: safety cap only; the real limit is per day (six), enforced by the editor and HT.
            // Weekday keys: a v1 HT rejects >3 in one half-day and quarantines the whole file.
            val cap = if (dated) MAX_DELIVERY_JOBS_PER_DAY else MAX_DELIVERY_JOBS_PER_LEGACY_SLOT
            val kept = mutableListOf<DeliveryJob>()
            val dropped = mutableListOf<DeliveryJob>()
            val keptIdentities = mutableSetOf<String>()
            for (job in jobs) {
                val valid = job.jobNumber.isNotBlank() && job.description.isNotBlank() &&
                    (!dated || job.folderName.isNotBlank())
                when {
                    !valid -> dropped += job
                    // The same job twice in one slot is one job, not a loss: collapse it silently.
                    !keptIdentities.add(job.identity()) -> Unit
                    kept.size >= cap -> dropped += job
                    else -> kept += job
                }
            }
            if (dated && jobs.isNotEmpty() && kept.isEmpty()) {
                // Writing an empty list would make HT clear the slot, which nobody asked for.
                return@withContext DeliveryQueueResult(false, jobs, DeliveryQueueFailure.AllJobsInvalid)
            }
            lockFor(tabletId).withLock {
                val existing = when (val e = readExisting(tabletId)) {
                    Existing.Blocked -> return@withLock DeliveryQueueResult(false, jobs, DeliveryQueueFailure.Blocked)
                    Existing.Missing -> null
                    is Existing.Parsed -> e.request
                }
                val written = writeRequest(
                    tabletId,
                    DeliveryScheduleEditRequest(
                        tabletId = tabletId,
                        requestedAt = Instant.now().toString(),
                        resetAll = existing?.resetAll ?: false,
                        slotEdits = existing?.slotEdits.orEmpty().filterNot { it.slot == normalizedSlot } +
                            DeliveryScheduleSlotEdit(slot = normalizedSlot, jobs = kept)
                    )
                )
                if (written) DeliveryQueueResult(true, dropped)
                else DeliveryQueueResult(false, jobs, DeliveryQueueFailure.Blocked)
            }
        }

    override suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?): Boolean =
        withContext(Dispatchers.IO) {
            lockFor(tabletId).withLock {
                val existing = when (val e = readExisting(tabletId)) {
                    Existing.Blocked -> {
                        Log.w(TAG, "queueReset skipped: ${requestFile(tabletId).name} could not be read or backed up")
                        return@withLock false
                    }
                    Existing.Missing -> null
                    is Existing.Parsed -> e.request
                }
                // HT applies resetAll first, then slotEdits. Edits for slots the reset clears (Mon-Fri of
                // the mirrored week, or HT's own rule from today when the file has no weekOf) would
                // re-apply stale intent, so drop them; edits for other weeks / weekends survive.
                val resetStart = mirroredWeekOf ?: mirrorWeekOf(today())
                val resetEnd = resetStart.plusDays(4)
                val survivors = existing?.slotEdits.orEmpty().filter { edit ->
                    val date = parseDatedSlotKey(edit.slot)?.date ?: return@filter false
                    date < resetStart || date > resetEnd
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
        return runCatching { parseRequest(file.readText()) }.getOrNull()
    }

    private fun parseRequest(text: String): DeliveryScheduleEditRequest? =
        runCatching { gson.fromJson(text, DeliveryScheduleEditRequest::class.java) }
            .getOrNull()
            // Gson bypasses Kotlin's non-null types, so a file without slotEdits parses with null.
            ?.takeIf { it.slotEdits != null }

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
        parseRequest(text)?.let { return Existing.Parsed(it) }
        val backup = File(file.parentFile, "${file.name}$BACKUP_INFIX${clockMillis()}")
        try {
            file.copyTo(backup, overwrite = false)
        } catch (e: Exception) {
            Log.w(TAG, "Could not back up unreadable ${file.name}; leaving it untouched", e)
            return Existing.Blocked
        }
        Log.w(TAG, "Unreadable ${file.name}; kept a copy as ${backup.name} and started a new request")
        pruneBackups(file)
        return Existing.Missing
    }

    /** Keeps only the [MAX_UNREADABLE_BACKUPS] newest `.unreadable-<millis>` copies of [requestFile]. */
    private fun pruneBackups(requestFile: File) {
        val prefix = "${requestFile.name}$BACKUP_INFIX"
        val backups = requestFile.parentFile?.listFiles { f -> f.isFile && f.name.startsWith(prefix) }.orEmpty()
        if (backups.size <= MAX_UNREADABLE_BACKUPS) return
        backups
            .sortedWith(
                compareByDescending<File> { it.name.removePrefix(prefix).toLongOrNull() ?: it.lastModified() }
                    .thenByDescending { it.name }
            )
            .drop(MAX_UNREADABLE_BACKUPS)
            .forEach { old ->
                if (!old.delete()) Log.w(TAG, "Could not delete old backup ${old.name}")
            }
    }

    /** True once the request is on disk; false (original untouched) when the write failed. */
    private fun writeRequest(tabletId: String, payload: DeliveryScheduleEditRequest): Boolean =
        try {
            atomicWriteFile(requestFile(tabletId), gson.toJson(payload))
            true
        } catch (e: IOException) {
            Log.w(TAG, "Could not write ${requestFile(tabletId).name}", e)
            false
        }

    private fun lockFor(tabletId: String): Mutex =
        locks.getOrPut(requestFile(tabletId).absolutePath) { Mutex() }

    private fun DeliveryJob.identity(): String =
        if (folderName.isNotBlank()) "f:${folderName.lowercase()}" else "n:$jobNumber"

    private companion object {
        const val TAG = "DeliveryRequestStore"
        const val BACKUP_INFIX = ".unreadable-"
        const val MAX_UNREADABLE_BACKUPS = 5
        /** Process-wide so two store instances for the same file still serialize. */
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
