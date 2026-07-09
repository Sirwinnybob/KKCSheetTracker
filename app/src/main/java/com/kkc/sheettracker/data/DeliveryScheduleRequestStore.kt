package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import com.kkc.sheettracker.data.models.DeliveryJob
import java.io.File
import java.time.Instant

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

/**
 * Queues tablet-authored schedule edits for the Hours Tracker backend to apply to the master
 * `.metadata/delivery_schedule.json`. This mirrors the production/job-board request pattern:
 * Android writes one request file per tablet, the server consumes it, then Android re-reads
 * master state.
 *
 * CROSS-PROGRAM (see METADATA_AUDIT.md M-04): request files are now named
 * `delivery_schedule_request.<tabletId>.json` (one per tablet) instead of a single shared
 * filename — two tablets queuing edits before the same poll cycle no longer collide (lost
 * update / unread `.sync-conflict-*` copy). The backend poller (`main_v2.py`
 * `_apply_delivery_schedule_requests`) globs all matching files and applies them in timestamp
 * order. Note: the read-modify-write below still has no in-process lock — tracked separately as
 * METADATA_AUDIT.md L-06 and intentionally NOT fixed here; only the cross-device filename
 * collision is in scope for M-04.
 */
class DeliveryScheduleRequestStore(private val baseDir: File) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun requestFile(tabletId: String) = File(baseDir, "delivery_schedule_request.$tabletId.json")

    fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String) {
        val normalizedSlot = slot.trim().lowercase()
        if (normalizedSlot.isBlank()) return
        val existing = readExisting(tabletId)
        val updatedEdits = existing.slotEdits
            .filterNot { it.slot == normalizedSlot } +
            DeliveryScheduleSlotEdit(slot = normalizedSlot, jobs = jobs.take(3))
        writeRequest(
            tabletId,
            DeliveryScheduleEditRequest(
                tabletId = tabletId,
                requestedAt = Instant.now().toString(),
                resetAll = existing.resetAll,
                slotEdits = updatedEdits
            )
        )
    }

    fun queueReset(tabletId: String) {
        writeRequest(
            tabletId,
            DeliveryScheduleEditRequest(
                tabletId = tabletId,
                requestedAt = Instant.now().toString(),
                resetAll = true,
                slotEdits = emptyList()
            )
        )
    }

    private fun readExisting(tabletId: String): DeliveryScheduleEditRequest {
        val file = requestFile(tabletId)
        if (!file.exists()) {
            return DeliveryScheduleEditRequest(tabletId = "", requestedAt = "")
        }
        return runCatching {
            gson.fromJson(file.readText(), DeliveryScheduleEditRequest::class.java)
        }.getOrNull() ?: DeliveryScheduleEditRequest(tabletId = "", requestedAt = "")
    }

    private fun writeRequest(tabletId: String, payload: DeliveryScheduleEditRequest) {
        atomicWriteFile(requestFile(tabletId), gson.toJson(payload))
    }
}
