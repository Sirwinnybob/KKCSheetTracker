package com.kkc.sheettracker.data.models

data class DeliveryJob(
    val jobNumber: String = "",
    val description: String = "",
    val address: String = ""
)

data class DeliverySlot(
    val jobs: List<DeliveryJob> = emptyList()
)

data class DeliverySchedule(
    val slots: Map<String, DeliverySlot> = emptyMap()
) {
    fun slot(day: String, period: String): DeliverySlot =
        slots["${day.lowercase()}_${period.lowercase()}"] ?: DeliverySlot()

    val isEmpty: Boolean
        get() = slots.values.all { it.jobs.isEmpty() }
}

val DELIVERY_DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday")
val DELIVERY_DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
val DELIVERY_PERIODS = listOf("am", "pm")
