package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryScheduleBannerTest {

    private val populatedSchedule = DeliverySchedule(
        slots = mapOf(
            "monday_am" to DeliverySlot(
                jobs = listOf(
                    DeliveryJob(jobNumber = "1", description = "A"),
                    DeliveryJob(jobNumber = "2", description = "B")
                )
            ),
            "wednesday_pm" to DeliverySlot(
                jobs = listOf(DeliveryJob(jobNumber = "3", description = "C"))
            )
        )
    )

    @Test
    fun shouldShowDeliveryScheduleBanner_hidesEmptyScheduleForNonAdmins() {
        assertFalse(shouldShowDeliveryScheduleBanner(DeliverySchedule(), showWhenEmpty = false))
    }

    @Test
    fun shouldShowDeliveryScheduleBanner_showsEmptyScheduleForAdmins() {
        assertTrue(shouldShowDeliveryScheduleBanner(DeliverySchedule(), showWhenEmpty = true))
    }

    @Test
    fun shouldShowDeliveryScheduleBanner_showsPopulatedScheduleForEveryone() {
        assertTrue(shouldShowDeliveryScheduleBanner(populatedSchedule, showWhenEmpty = false))
    }

    @Test
    fun totalDeliveryCount_sumsJobsAcrossAllSlots() {
        assertEquals(3, totalDeliveryCount(populatedSchedule))
    }

    @Test
    fun totalDeliveryCount_zeroForEmptySchedule() {
        assertEquals(0, totalDeliveryCount(DeliverySchedule()))
    }

    @Test
    fun daysWithDeliveries_returnsOnlyDaysThatHaveAtLeastOneJob() {
        assertEquals(setOf("monday", "wednesday"), daysWithDeliveries(populatedSchedule))
    }

    @Test
    fun daysWithDeliveries_emptyForEmptySchedule() {
        assertTrue(daysWithDeliveries(DeliverySchedule()).isEmpty())
    }

    @Test
    fun deliveryDayState_pastForDayBeforeToday() {
        // Monday(0) vs today=Wednesday -> PAST
        assertEquals(DeliveryDayState.PAST, deliveryDayState(0, DayOfWeek.WEDNESDAY))
    }

    @Test
    fun deliveryDayState_todayForMatchingDay() {
        // Wednesday(2) vs today=Wednesday -> TODAY
        assertEquals(DeliveryDayState.TODAY, deliveryDayState(2, DayOfWeek.WEDNESDAY))
    }

    @Test
    fun deliveryDayState_futureForDayAfterToday() {
        // Friday(4) vs today=Wednesday -> FUTURE
        assertEquals(DeliveryDayState.FUTURE, deliveryDayState(4, DayOfWeek.WEDNESDAY))
    }

    @Test
    fun deliveryDayState_allPastOnSaturday() {
        assertEquals(DeliveryDayState.PAST, deliveryDayState(0, DayOfWeek.SATURDAY))
        assertEquals(DeliveryDayState.PAST, deliveryDayState(4, DayOfWeek.SATURDAY))
    }

    @Test
    fun deliveryDayState_allPastOnSunday() {
        assertEquals(DeliveryDayState.PAST, deliveryDayState(0, DayOfWeek.SUNDAY))
        assertEquals(DeliveryDayState.PAST, deliveryDayState(4, DayOfWeek.SUNDAY))
    }
}
