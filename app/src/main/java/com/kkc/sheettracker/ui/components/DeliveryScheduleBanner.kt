package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS
import com.kkc.sheettracker.data.models.DeliverySchedule
import java.time.DayOfWeek

internal fun shouldShowDeliveryScheduleBanner(
    schedule: DeliverySchedule,
    showWhenEmpty: Boolean
): Boolean = showWhenEmpty || !schedule.isEmpty

internal fun totalDeliveryCount(schedule: DeliverySchedule): Int =
    schedule.slots.values.sumOf { it.jobs.size }

internal fun daysWithDeliveries(schedule: DeliverySchedule): Set<String> =
    DELIVERY_DAYS.filter { day ->
        DELIVERY_PERIODS.any { period -> schedule.slot(day, period).jobs.isNotEmpty() }
    }.toSet()

internal enum class DeliveryDayState { PAST, TODAY, FUTURE }

internal fun deliveryDayState(dayIndex: Int, today: DayOfWeek): DeliveryDayState {
    val todayIndex = today.value - 1 // DayOfWeek.MONDAY.value == 1 -> index 0
    return when {
        todayIndex !in DELIVERY_DAYS.indices -> DeliveryDayState.PAST // weekend: whole week is past
        dayIndex < todayIndex -> DeliveryDayState.PAST
        dayIndex == todayIndex -> DeliveryDayState.TODAY
        else -> DeliveryDayState.FUTURE
    }
}
