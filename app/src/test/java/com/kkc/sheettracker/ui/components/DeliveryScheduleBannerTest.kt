package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.buildDeliveryWeek
import com.kkc.sheettracker.data.defaultDeliveryWeekStart
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun weekendSchedule(vararg slotKeys: String) =
        slotKeys.fold(DeliverySchedule(isDated = true, weekOf = mon.plusWeeks(1))) { s, key -> s.withSlot(key, listOf(job)) }

    private fun weekendDays(s: DeliverySchedule, today: java.time.LocalDate) =
        com.kkc.sheettracker.data.weekendDays(s, today, defaultDeliveryWeekStart(s, today))

    @Test
    fun staleWeekOfDoesNotDuplicateSaturdayInTheBanner() {
        // HT has not rolled weekOf yet: it is still this Monday, so the week view already holds Saturday.
        val s = DeliverySchedule(isDated = true, weekOf = mon).withSlot("2026-10-17_am", listOf(job))
        val w = week(s, sat)
        val weekend = weekendDays(s, sat)
        assertTrue(weekend.isEmpty())
        assertEquals(1, w.days.count { it.date == sat })
        assertEquals(1, w.totalCount)
        assertEquals("Deliveries This Week", deliveryBannerTitle(w, sat, weekend))
        assertTrue(shouldShowDeliveryScheduleBanner(w, showWhenEmpty = false, weekendDays = weekend))
    }

    @Test
    fun saturdayWithTodaysJobTitlesTodayAndNextWeek() {
        val s = weekendSchedule("2026-10-17_am")
        val weekend = weekendDays(s, sat)
        assertEquals(listOf(sat), weekend.map { it.date })
        assertEquals("Deliveries Today & Next Week", deliveryBannerTitle(week(s, sat), sat, weekend))
    }

    @Test
    fun sundayWithTodaysJobTitlesTodayAndNextWeek() {
        val s = weekendSchedule("2026-10-18_pm")
        val weekend = weekendDays(s, sun)
        assertEquals(listOf(sun), weekend.map { it.date })
        assertEquals("Deliveries Today & Next Week", deliveryBannerTitle(week(s, sun), sun, weekend))
    }

    @Test
    fun saturdayWithOnlySundayJobsTitlesThisWeekendAndNextWeek() {
        val s = weekendSchedule("2026-10-18_am")
        val weekend = weekendDays(s, sat)
        assertEquals(listOf(sun), weekend.map { it.date })
        assertEquals("Deliveries This Weekend & Next Week", deliveryBannerTitle(week(s, sat), sat, weekend))
        assertTrue(shouldShowDeliveryScheduleBanner(week(s, sat), showWhenEmpty = false, weekendDays = weekend))
    }

    @Test
    fun saturdayWithJobsBothWeekendDaysListsBothAndTitlesToday() {
        val s = weekendSchedule("2026-10-17_am", "2026-10-18_pm")
        val weekend = weekendDays(s, sat)
        assertEquals(listOf(sat, sun), weekend.map { it.date })
        assertEquals("Deliveries Today & Next Week", deliveryBannerTitle(week(s, sat), sat, weekend))
        // The header count is the week's plus every listed weekend day.
        assertEquals(2, weekend.sumOf { it.count })
    }

    @Test
    fun saturdayWithNoWeekendJobsKeepsNextWeekTitle() {
        val s = weekendSchedule("2026-10-21_am")
        val weekend = weekendDays(s, sat)
        assertTrue(weekend.isEmpty())
        assertEquals("Deliveries Next Week", deliveryBannerTitle(week(s, sat), sat, weekend))
    }

    @Test
    fun weekdayHasNoWeekendDaysAndKeepsThisWeekTitle() {
        val s = DeliverySchedule(isDated = true, weekOf = mon).withSlot("2026-10-14_am", listOf(job))
        val wed = java.time.LocalDate.of(2026, 10, 14)
        val weekend = weekendDays(s, wed)
        assertTrue(weekend.isEmpty())
        assertEquals("Deliveries This Week", deliveryBannerTitle(week(s, wed), wed, weekend))
    }

    @Test
    fun v1ScheduleHasNoWeekendDays() {
        assertTrue(weekendDays(DeliverySchedule(), sat).isEmpty())
    }

    @Test
    fun bannerShowsForNonAdminWhenOnlyWeekendDaysHaveJobs() {
        val s = weekendSchedule("2026-10-17_am")
        val w = week(s, sat)
        assertEquals(0, w.totalCount)
        assertFalse(shouldShowDeliveryScheduleBanner(w, showWhenEmpty = false))
        assertTrue(shouldShowDeliveryScheduleBanner(w, showWhenEmpty = false, weekendDays = weekendDays(s, sat)))
    }
}
