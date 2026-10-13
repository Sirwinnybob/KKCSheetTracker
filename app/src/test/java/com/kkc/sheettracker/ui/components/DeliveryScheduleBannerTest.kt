package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.buildDeliveryWeek
import com.kkc.sheettracker.data.defaultDeliveryWeekStart
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryScheduleBannerTest {

    private val mon = java.time.LocalDate.of(2026, 10, 12)
    private val job = DeliveryJob("670", "E", folderName = "670 - E")

    private fun week(schedule: DeliverySchedule, today: java.time.LocalDate) =
        buildDeliveryWeek(schedule, defaultDeliveryWeekStart(schedule, today), today, includeEmptyWeekend = false)

    @Test
    fun bannerHiddenWhenWeekEmptyForNonAdminShownForAdmin() {
        val w = week(DeliverySchedule(isDated = true, weekOf = mon), mon)
        assertFalse(shouldShowDeliveryScheduleBanner(w, showWhenEmpty = false))
        assertTrue(shouldShowDeliveryScheduleBanner(w, showWhenEmpty = true))
    }

    @Test
    fun bannerIgnoresJobsOutsideItsWeek() {
        val s = DeliverySchedule(isDated = true, weekOf = mon).withSlot("2026-10-21_am", listOf(job))
        val w = week(s, mon)
        assertEquals(0, w.totalCount)
        assertFalse(shouldShowDeliveryScheduleBanner(w, showWhenEmpty = false))
    }

    @Test
    fun daysWithDeliveriesAreDates() {
        val s = DeliverySchedule(isDated = true, weekOf = mon).withSlot("2026-10-14_pm", listOf(job))
        assertEquals(setOf(java.time.LocalDate.of(2026, 10, 14)), daysWithDeliveries(week(s, mon)))
    }

    @Test
    fun titleSaysNextWeekOnWeekendRollover() {
        val sat = java.time.LocalDate.of(2026, 10, 17)
        val s = DeliverySchedule(isDated = true, weekOf = mon.plusWeeks(1))
        assertEquals("Deliveries Next Week", deliveryBannerTitle(week(s, sat), sat))
        assertEquals("Deliveries This Week", deliveryBannerTitle(week(DeliverySchedule(isDated = true, weekOf = mon), mon), mon))
    }

    private val sat = java.time.LocalDate.of(2026, 10, 17)
    private val sun = java.time.LocalDate.of(2026, 10, 18)

    private fun weekendSchedule(slotKey: String) =
        DeliverySchedule(isDated = true, weekOf = mon.plusWeeks(1)).withSlot(slotKey, listOf(job))

    private fun weekendTodayDay(s: DeliverySchedule, today: java.time.LocalDate) =
        com.kkc.sheettracker.data.weekendTodayDay(s, today, defaultDeliveryWeekStart(s, today))

    @Test
    fun staleWeekOfDoesNotDuplicateSaturdayInTheBanner() {
        // HT has not rolled weekOf yet: it is still this Monday, so the week view already holds Saturday.
        val s = DeliverySchedule(isDated = true, weekOf = mon).withSlot("2026-10-17_am", listOf(job))
        val w = week(s, sat)
        val today = weekendTodayDay(s, sat)
        assertNull(today)
        assertEquals(1, w.days.count { it.date == sat })
        assertEquals(1, w.totalCount)
        assertEquals("Deliveries This Week", deliveryBannerTitle(w, sat, today))
        assertTrue(shouldShowDeliveryScheduleBanner(w, showWhenEmpty = false, weekendToday = today))
    }

    @Test
    fun saturdayWithTodaysJobTitlesTodayAndNextWeek() {
        val s = weekendSchedule("2026-10-17_am")
        val today = weekendTodayDay(s, sat)
        assertNotNull(today)
        assertEquals("Deliveries Today & Next Week", deliveryBannerTitle(week(s, sat), sat, today))
    }

    @Test
    fun sundayWithTodaysJobTitlesTodayAndNextWeek() {
        val s = weekendSchedule("2026-10-18_pm")
        val today = weekendTodayDay(s, sun)
        assertNotNull(today)
        assertEquals("Deliveries Today & Next Week", deliveryBannerTitle(week(s, sun), sun, today))
    }

    @Test
    fun saturdayWithNoJobTodayKeepsNextWeekTitle() {
        val s = weekendSchedule("2026-10-21_am")
        val today = weekendTodayDay(s, sat)
        assertNull(today)
        assertEquals("Deliveries Next Week", deliveryBannerTitle(week(s, sat), sat, today))
    }

    @Test
    fun weekdayHasNoWeekendTodayAndKeepsThisWeekTitle() {
        val s = DeliverySchedule(isDated = true, weekOf = mon).withSlot("2026-10-14_am", listOf(job))
        val wed = java.time.LocalDate.of(2026, 10, 14)
        val today = weekendTodayDay(s, wed)
        assertNull(today)
        assertEquals("Deliveries This Week", deliveryBannerTitle(week(s, wed), wed, today))
    }

    @Test
    fun v1ScheduleHasNoWeekendToday() {
        assertNull(weekendTodayDay(DeliverySchedule(), sat))
    }

    @Test
    fun bannerShowsForNonAdminWhenOnlyWeekendTodayHasJobs() {
        val s = weekendSchedule("2026-10-17_am")
        val w = week(s, sat)
        assertEquals(0, w.totalCount)
        assertFalse(shouldShowDeliveryScheduleBanner(w, showWhenEmpty = false))
        assertTrue(shouldShowDeliveryScheduleBanner(w, showWhenEmpty = false, weekendToday = weekendTodayDay(s, sat)))
    }
}
