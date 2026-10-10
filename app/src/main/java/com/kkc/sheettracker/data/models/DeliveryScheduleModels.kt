package com.kkc.sheettracker.data.models

import java.time.LocalDate

data class DeliveryJob(
    val jobNumber: String = "",
    val description: String = "",
    val address: String = "",
    val folderName: String = ""
)

data class DeliverySlot(
    val jobs: List<DeliveryJob> = emptyList()
)

/**
 * Delivery schedule as published by Hours Tracker (`.metadata/delivery_schedule.json`).
 *
 * - [slots]: Monday-Friday mirror keyed `monday_am`..`friday_pm` (v1 shape, still written by v2).
 * - [datedSlots]: v2 canonical slots keyed `YYYY-MM-DD_am|pm` in the shop's local calendar,
 *   including weekend dates. Empty for a v1 document.
 * - [weekOf]: Monday of the week mirrored in [slots] (next Monday from Saturday 00:00 shop time).
 * - [isDated]: the document carried a `datedSlots` object (the server is v2-aware).
 */
data class DeliverySchedule(
    val slots: Map<String, DeliverySlot> = emptyMap(),
    val datedSlots: Map<String, DeliverySlot> = emptyMap(),
    val weekOf: LocalDate? = null,
    val isDated: Boolean = false
) {
    fun slot(day: String, period: String): DeliverySlot =
        slots["${day.lowercase()}_${period.lowercase()}"] ?: DeliverySlot()

    fun slotByKey(key: String): DeliverySlot =
        (if (parseDatedSlotKey(key) != null) datedSlots[key] else slots[key]) ?: DeliverySlot()

    fun withSlot(key: String, jobs: List<DeliveryJob>): DeliverySchedule =
        if (parseDatedSlotKey(key) != null) copy(datedSlots = datedSlots + (key to DeliverySlot(jobs)))
        else copy(slots = slots + (key to DeliverySlot(jobs)))

    val isEmpty: Boolean
        get() = slots.values.all { it.jobs.isEmpty() } && datedSlots.values.all { it.jobs.isEmpty() }
}

/** A job HT refused to place from an admin-sync batch (409 full, 404 folder missing, 400 closed job). */
data class DeliveryScheduleFailure(
    val slot: String,
    val jobNumber: String,
    val folderName: String,
    val status: Int,
    val reason: String
)

/** A parsed schedule document plus any per-job `failures` (admin-sync / per-job PUT responses only). */
data class DeliveryScheduleDocument(
    val schedule: DeliverySchedule,
    val failures: List<DeliveryScheduleFailure> = emptyList()
)

data class DeliverySchedulePickerJob(
    val folderName: String,
    val jobNumber: String,
    val description: String
)

data class DatedSlotKey(val date: LocalDate, val period: String)

private val DATED_SLOT_KEY = Regex("""^(\d{4}-\d{2}-\d{2})_(am|pm)$""")

/** Parses a lower-case `YYYY-MM-DD_am|pm` key; null for any other form or an impossible date. */
fun parseDatedSlotKey(key: String): DatedSlotKey? {
    val match = DATED_SLOT_KEY.matchEntire(key) ?: return null
    val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
    return DatedSlotKey(date, match.groupValues[2])
}

fun datedSlotKey(date: LocalDate, period: String): String = "${date}_${period.lowercase()}"

fun isWeekdaySlotKey(key: String): Boolean {
    val parts = key.split('_')
    return parts.size == 2 && parts[0] in DELIVERY_DAYS && parts[1] in DELIVERY_PERIODS
}

val DELIVERY_DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday")
val DELIVERY_DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
val DELIVERY_PERIODS = listOf("am", "pm")
