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
 * Android writes one request file, the server consumes it, then Android re-reads master state.
 */
class DeliveryScheduleRequestStore(private val baseDir: File) {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val requestFile get() = File(baseDir, "delivery_schedule_request.json")

    fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String) {
        val normalizedSlot = slot.trim().lowercase()
        if (normalizedSlot.isBlank()) return
        val existing = readExisting()
        val updatedEdits = existing.slotEdits
            .filterNot { it.slot == normalizedSlot } +
            DeliveryScheduleSlotEdit(slot = normalizedSlot, jobs = jobs.take(3))
        writeRequest(
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
            DeliveryScheduleEditRequest(
                tabletId = tabletId,
                requestedAt = Instant.now().toString(),
                resetAll = true,
                slotEdits = emptyList()
            )
        )
    }

    private fun readExisting(): DeliveryScheduleEditRequest {
        if (!requestFile.exists()) {
            return DeliveryScheduleEditRequest(tabletId = "", requestedAt = "")
        }
        return runCatching {
            gson.fromJson(requestFile.readText(), DeliveryScheduleEditRequest::class.java)
        }.getOrNull() ?: DeliveryScheduleEditRequest(tabletId = "", requestedAt = "")
    }

    private fun writeRequest(payload: DeliveryScheduleEditRequest) {
        val tmp = File(baseDir, "delivery_schedule_request.json.tmp")
        tmp.writeText(gson.toJson(payload))
        if (!tmp.renameTo(requestFile)) {
            tmp.copyTo(requestFile, overwrite = true)
            tmp.delete()
        }
    }
}
