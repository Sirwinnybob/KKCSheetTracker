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
}
