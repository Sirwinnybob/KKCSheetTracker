package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliveryScheduleDocument
import com.kkc.sheettracker.data.models.DeliveryScheduleFailure
import com.kkc.sheettracker.data.models.DeliverySlot
import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS
import com.kkc.sheettracker.data.models.parseDatedSlotKey
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

private val deliveryScheduleGson = GsonBuilder().create()

/**
 * Parses every delivery-schedule document shape with ONE function:
 * - on-disk master file and live WebSocket `schedule` payload: `{schemaVersion, slots, datedSlots?, weekOf?}`
 * - `POST /api/admin-sync/delivery-schedule` response: same plus `failures`
 * - `GET`/`PUT /api/delivery-schedule[/jobs/{folder}]`: mirror under `schedule` instead of `slots`
 *
 * v1 documents (no `datedSlots`) parse exactly as before. A bad key, bad date or bad job inside
 * one slot is skipped without discarding the rest. Throws only when [json] is not JSON at all,
 * so HTTP callers keep falling back on garbage bodies.
 */
internal fun parseDeliveryScheduleDocument(json: String): DeliveryScheduleDocument {
    val root = deliveryScheduleGson.fromJson(json, JsonObject::class.java)
        ?: return DeliveryScheduleDocument(DeliverySchedule())
    val mirror = root.objectOrNull("slots") ?: root.objectOrNull("schedule")
    val slots = if (mirror == null) emptyMap() else parseWeekdaySlots(mirror)
    val datedObj = root.objectOrNull("datedSlots")
    val datedSlots = if (datedObj == null) emptyMap() else parseDatedSlots(datedObj)
    val weekOf = root.get("weekOf")
        ?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { LocalDate.parse(it.asString) }.getOrNull() }
        ?.takeIf { it.dayOfWeek == DayOfWeek.MONDAY }
    val schedule = DeliverySchedule(
        slots = slots,
        datedSlots = datedSlots,
        weekOf = if (datedObj != null) weekOf else null,
        isDated = datedObj != null
    )
    return DeliveryScheduleDocument(schedule, parseFailures(root))
}

internal fun parseDeliverySchedule(json: String): DeliverySchedule =
    parseDeliveryScheduleDocument(json).schedule

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun parseWeekdaySlots(slotsObj: JsonObject): Map<String, DeliverySlot> {
    val slots = mutableMapOf<String, DeliverySlot>()
    for (day in DELIVERY_DAYS) {
        for (period in DELIVERY_PERIODS) {
            val key = "${day}_${period}"
            slots[key] = DeliverySlot(jobs = parseJobs(slotsObj.objectOrNull(key)))
        }
    }
    return slots
}

private fun parseDatedSlots(datedObj: JsonObject): Map<String, DeliverySlot> =
    datedObj.entrySet().mapNotNull { (key, value) ->
        if (parseDatedSlotKey(key) == null || !value.isJsonObject) return@mapNotNull null
        key to DeliverySlot(jobs = parseJobs(value.asJsonObject))
    }.toMap()

private fun parseJobs(slotObj: JsonObject?): List<DeliveryJob> {
    val jobsArr = slotObj?.get("jobs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return jobsArr.mapNotNull { elem ->
        val obj = elem.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        DeliveryJob(
            jobNumber = obj.stringOrBlank("jobNumber"),
            description = obj.stringOrBlank("description"),
            address = obj.stringOrBlank("address"),
            folderName = obj.stringOrBlank("folderName")
        )
    }
}

private fun JsonObject.stringOrBlank(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

private fun parseFailures(root: JsonObject): List<DeliveryScheduleFailure> {
    val arr = root.get("failures")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return arr.mapNotNull { elem ->
        val obj = elem.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        DeliveryScheduleFailure(
            slot = obj.stringOrBlank("slot"),
            jobNumber = obj.stringOrBlank("jobNumber"),
            folderName = obj.stringOrBlank("folderName"),
            status = obj.get("status")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0,
            reason = obj.stringOrBlank("reason")
        )
    }
}

/**
 * Reads the delivery schedule from the shared network drive.
 * Storage path: {baseDir}/.metadata/delivery_schedule.json
 * Written by kkc-admin; read-only on the tablet.
 * Call on Dispatchers.IO.
 */
class DeliveryScheduleRepository(private val baseDir: File) {

    fun fetchSchedule(): DeliverySchedule {
        val file = File(baseDir, ".metadata/delivery_schedule.json")
        if (!file.exists() || !file.isFile) return DeliverySchedule()
        return runCatching { parseDeliverySchedule(file.readText()) }.getOrElse { DeliverySchedule() }
    }
}
