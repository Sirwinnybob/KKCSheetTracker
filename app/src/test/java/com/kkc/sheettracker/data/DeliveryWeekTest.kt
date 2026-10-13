package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

class DeliveryWeekTest {
    private val mon = LocalDate.of(2026, 10, 12)
    private val wed = LocalDate.of(2026, 10, 14)
    private val sat = LocalDate.of(2026, 10, 17)
    private val sun = LocalDate.of(2026, 10, 18)
    private val nextMon = LocalDate.of(2026, 10, 19)
    private val job = DeliveryJob("670", "ESCHRICH", folderName = "670 - ESCHRICH")

    private fun v2(weekOf: LocalDate, vararg dated: Pair<String, DeliveryJob>) = DeliverySchedule(
        slots = emptyMap(),
        datedSlots = dated.groupBy({ it.first }, { it.second }).mapValues { DeliverySlot(it.value) },
        weekOf = weekOf,
        isDated = true
    )

    @Test
    fun shopTodayUsesLosAngelesNotUtc() {
        // 2026-10-15T03:00Z is still Oct 14 in Oregon.
        val clock = Clock.fixed(Instant.parse("2026-10-15T03:00:00Z"), java.time.ZoneOffset.UTC)
        assertEquals(wed, shopToday(clock))
    }

    @Test
    fun mirrorWeekOfFollowsSaturdayRollover() {
        assertEquals(mon, mirrorWeekOf(mon))
        assertEquals(mon, mirrorWeekOf(LocalDate.of(2026, 10, 16)))
        assertEquals(nextMon, mirrorWeekOf(sat))
        assertEquals(nextMon, mirrorWeekOf(sun))
    }

    @Test
    fun thisWeekFollowsWeekOf() {
        assertEquals(nextMon, defaultDeliveryWeekStart(v2(nextMon), today = wed))
    }

    @Test
    fun v2WithoutWeekOfFallsBackToMirrorRule() {
        assertEquals(nextMon, defaultDeliveryWeekStart(DeliverySchedule(isDated = true), today = sat))
    }

    @Test
    fun v1WeekIsTheWeekContainingToday() {
        assertEquals(mon, defaultDeliveryWeekStart(DeliverySchedule(), today = sat))
    }

    @Test
    fun realDatesMatchKeysAndLabels() {
        val week = buildDeliveryWeek(v2(mon, "2026-10-14_am" to job), mon, today = mon, includeEmptyWeekend = false)
        assertEquals(5, week.days.size)
        val tue = week.days[1]
        assertEquals(LocalDate.of(2026, 10, 13), tue.date)
        assertEquals("Tue 10/13", tue.label)
        assertEquals("2026-10-13_am", tue.amKey)
        assertEquals("2026-10-13_pm", tue.pmKey)
        assertEquals(listOf(job), week.days[2].am.jobs)
        assertEquals(1, week.totalCount)
    }

    private fun jobs(prefix: String, count: Int) =
        (1..count).map { DeliveryJob("$prefix$it", "J$prefix$it", folderName = "$prefix$it - J") }

    @Test
    fun sixJobDaySplitFourAmTwoPmCountsSixAndKeepsBothHalves() {
        val am = jobs("1", 4)
        val pm = jobs("2", 2)
        val schedule = DeliverySchedule(
            datedSlots = mapOf(
                "2026-10-14_am" to DeliverySlot(am),
                "2026-10-14_pm" to DeliverySlot(pm)
            ),
            weekOf = mon,
            isDated = true
        )
        val day = buildDeliveryWeek(schedule, mon, today = mon, includeEmptyWeekend = false).days[2]
        assertEquals(6, day.count)
        assertEquals(am, day.am.jobs)
        assertEquals(pm, day.pm.jobs)
        assertEquals(MAX_DELIVERY_JOBS_PER_DAY, deliveryDayCount(schedule, "2026-10-14_am"))
    }

    @Test
    fun deliveryDayCountSumsBothHalvesForDatedAndWeekdayKeys() {
        val dated = DeliverySchedule(
            datedSlots = mapOf("2026-10-14_am" to DeliverySlot(jobs("1", 3)), "2026-10-14_pm" to DeliverySlot(jobs("2", 1)), "2026-10-15_am" to DeliverySlot(jobs("3", 5))),
            weekOf = mon,
            isDated = true
        )
        assertEquals(4, deliveryDayCount(dated, "2026-10-14_am"))
        assertEquals(4, deliveryDayCount(dated, "2026-10-14_pm"))
        assertEquals(5, deliveryDayCount(dated, "2026-10-15_pm"))
        assertEquals(0, deliveryDayCount(dated, "2026-10-16_am"))

        val weekday = DeliverySchedule(slots = mapOf("monday_am" to DeliverySlot(jobs("4", 2)), "monday_pm" to DeliverySlot(jobs("5", 3)), "tuesday_am" to DeliverySlot(jobs("6", 1))))
        assertEquals(5, deliveryDayCount(weekday, "monday_am"))
        assertEquals(5, deliveryDayCount(weekday, "monday_pm"))
        assertEquals(1, deliveryDayCount(weekday, "tuesday_pm"))

        // Unknown keys fall back to the slot's own size.
        assertEquals(0, deliveryDayCount(weekday, "someday_noon"))
    }

    @Test
    fun jobWithOnlyAnAmKeyAndNoPmIsShownInThatDaysAm() {
        val schedule = v2(mon, "2026-10-14_am" to job)
        val day = buildDeliveryWeek(schedule, mon, today = mon, includeEmptyWeekend = false).days[2]
        assertEquals(listOf(job), day.am.jobs)
        assertTrue(day.pm.jobs.isEmpty())
        assertEquals(1, day.count)
    }

    @Test
    fun weekendDayShownOnlyWhenItHasJobsUnlessRequested() {
        val schedule = v2(mon, "2026-10-18_am" to job)
        val banner = buildDeliveryWeek(schedule, mon, today = mon, includeEmptyWeekend = false)
        assertEquals(listOf(12, 13, 14, 15, 16, 18), banner.days.map { it.date.dayOfMonth })
        val editor = buildDeliveryWeek(schedule, mon, today = mon, includeEmptyWeekend = true)
        assertEquals(7, editor.days.size)
    }

    @Test
    fun weekendTodayShowsNextWeekAsFuture() {
        listOf(sat, sun).forEach { today ->
            val schedule = v2(nextMon)
            val week = buildDeliveryWeek(schedule, defaultDeliveryWeekStart(schedule, today), today, includeEmptyWeekend = false)
            assertEquals(nextMon, week.weekStart)
            assertTrue(week.days.all { it.state == DeliveryDayState.FUTURE })
        }
    }

    @Test
    fun v1SaturdayIsAllPastLikeBefore() {
        val schedule = DeliverySchedule(slots = mapOf("monday_am" to DeliverySlot(listOf(job))))
        val week = buildDeliveryWeek(schedule, defaultDeliveryWeekStart(schedule, sat), sat, includeEmptyWeekend = false)
        assertEquals("monday_am", week.days[0].amKey)
        assertEquals(listOf(job), week.days[0].am.jobs)
        assertTrue(week.days.all { it.state == DeliveryDayState.PAST })
        assertFalse(week.canGoBack)
        assertFalse(week.canGoForward)
    }

    @Test
    fun dayStateUsesDatesNotWeekdayIndex() {
        val week = buildDeliveryWeek(v2(mon), mon, today = wed, includeEmptyWeekend = false)
        assertEquals(
            listOf(DeliveryDayState.PAST, DeliveryDayState.PAST, DeliveryDayState.TODAY, DeliveryDayState.FUTURE, DeliveryDayState.FUTURE),
            week.days.map { it.state }
        )
    }

    @Test
    fun navigationForwardAndBackWithHistoryLimit() {
        val schedule = v2(mon)
        val thisWeek = buildDeliveryWeek(schedule, mon, today = wed, includeEmptyWeekend = false)
        assertTrue(thisWeek.isDefaultWeek)
        assertTrue(thisWeek.canGoBack)
        assertTrue(thisWeek.canGoForward)
        val prev = buildDeliveryWeek(schedule, mon.minusWeeks(1), today = wed, includeEmptyWeekend = false)
        assertFalse(prev.isDefaultWeek)
        // Cutoff is Sep 30. Week of Sep 28 (ends Oct 4) is still reachable from Oct 5's week...
        assertTrue(prev.canGoBack)
        // ...but week of Sep 21 (ends Sep 27) is not, so Sep 28's week can't go back.
        val twoBack = buildDeliveryWeek(schedule, mon.minusWeeks(2), today = wed, includeEmptyWeekend = false)
        assertFalse(twoBack.canGoBack)
        val next = buildDeliveryWeek(schedule, mon.plusWeeks(1), today = wed, includeEmptyWeekend = false)
        assertTrue(next.canGoForward)
    }

    @Test
    fun placementDisabledMoreThan14DaysBack() {
        val week = buildDeliveryWeek(v2(mon), mon.minusWeeks(2), today = wed, includeEmptyWeekend = false)
        // Sep 28..Oct 2; cutoff is Sep 30 (Oct 14 - 14 days).
        assertEquals(listOf(false, false, true, true, true), week.days.map { it.acceptsPlacement })
    }

    @Test
    fun pendingOverlayMovesDatedJobInsteadOfDuplicatingIt() {
        val b = DeliveryJob("702", "SMITH", folderName = "702 - SMITH")
        val schedule = v2(mon, "2026-10-14_am" to job)
            .copy(slots = mapOf("wednesday_am" to DeliverySlot(listOf(job))))
        val move = DeliveryScheduleEditRequest(
            tabletId = "T1",
            requestedAt = "2026-10-14T17:00:00Z",
            slotEdits = listOf(DeliveryScheduleSlotEdit("2026-10-21_pm", listOf(job.copy(folderName = "670 - eschrich"))))
        )
        val moved = overlayPendingDeliveryRequest(schedule, move)
        assertEquals(listOf(job.copy(folderName = "670 - eschrich")), moved.slotByKey("2026-10-21_pm").jobs)
        assertTrue(moved.slotByKey("2026-10-14_am").jobs.isEmpty())
        assertTrue(moved.slotByKey("wednesday_am").jobs.isEmpty())

        val second = move.copy(
            slotEdits = move.slotEdits + DeliveryScheduleSlotEdit("2026-10-14_am", listOf(b))
        )
        val both = overlayPendingDeliveryRequest(schedule, second)
        assertEquals(listOf(b), both.slotByKey("2026-10-14_am").jobs)
        assertEquals(1, both.datedSlots.values.sumOf { s -> s.jobs.count { it.folderName.equals(job.folderName, true) } })
    }

    @Test
    fun pendingOverlayDoesNotDedupeBlankFolderNamesOrWeekdayOnlySchedules() {
        val noFolder = DeliveryJob("900", "MANUAL")
        val v1 = DeliverySchedule(slots = mapOf("monday_am" to DeliverySlot(listOf(noFolder))))
        val request = DeliveryScheduleEditRequest(
            tabletId = "T1",
            requestedAt = "2026-10-14T17:00:00Z",
            slotEdits = listOf(DeliveryScheduleSlotEdit("tuesday_am", listOf(noFolder)))
        )
        val overlaid = overlayPendingDeliveryRequest(v1, request)
        assertEquals(listOf(noFolder), overlaid.slotByKey("monday_am").jobs)
        assertEquals(listOf(noFolder), overlaid.slotByKey("tuesday_am").jobs)
    }

    @Test
    fun pendingOverlayAppliesQueuedSlotEditsAndReset() {
        val other = DeliveryJob("702", "SMITH", folderName = "702 - SMITH")
        val schedule = v2(mon, "2026-10-14_am" to job, "2026-10-21_am" to other)
        val request = DeliveryScheduleEditRequest(
            tabletId = "T1",
            requestedAt = "2026-10-14T17:00:00Z",
            resetAll = true,
            slotEdits = listOf(DeliveryScheduleSlotEdit("2026-10-15_pm", listOf(job)))
        )
        val overlaid = overlayPendingDeliveryRequest(schedule, request)
        // resetAll clears weekOf..weekOf+4 only; future week kept; then slot edits applied.
        assertTrue(overlaid.slotByKey("2026-10-14_am").jobs.isEmpty())
        assertEquals(listOf(other), overlaid.slotByKey("2026-10-21_am").jobs)
        assertEquals(listOf(job), overlaid.slotByKey("2026-10-15_pm").jobs)
        assertEquals(setOf("2026-10-15_pm"), pendingDeliverySlotKeys(request))
        assertEquals(schedule, overlayPendingDeliveryRequest(schedule, null))
    }

    @Test
    fun weekendTodayDayShowsSaturdayDeliveryAsToday() {
        val s = v2(nextMon, "2026-10-17_am" to job)
        val day = weekendTodayDay(s, sat)!!
        assertEquals(sat, day.date)
        assertEquals(DeliveryDayState.TODAY, day.state)
        assertEquals(listOf(job), day.am.jobs)
        assertEquals("2026-10-17_am", day.amKey)
        assertEquals("2026-10-17_pm", day.pmKey)
        assertTrue(day.acceptsPlacement)
        assertEquals(1, day.count)
    }

    @Test
    fun weekendTodayDayShowsSundayPmDelivery() {
        val s = v2(nextMon, "2026-10-18_pm" to job)
        val day = weekendTodayDay(s, sun)!!
        assertEquals(sun, day.date)
        assertEquals(DeliveryDayState.TODAY, day.state)
        assertEquals(listOf(job), day.pm.jobs)
    }

    @Test
    fun weekendTodayDayIsNullWithNoJobToday() {
        // A job on Sunday does not make Saturday visible.
        assertNull(weekendTodayDay(v2(nextMon, "2026-10-18_am" to job), sat))
        assertNull(weekendTodayDay(v2(nextMon), sat))
    }

    @Test
    fun weekendTodayDayIsNullOnAWeekday() {
        assertNull(weekendTodayDay(v2(mon, "2026-10-14_am" to job), wed))
    }

    @Test
    fun weekendTodayDayIsNullForV1Schedule() {
        val v1 = DeliverySchedule(slots = mapOf("monday_am" to DeliverySlot(listOf(job))))
        assertNull(weekendTodayDay(v1, sat))
    }
}
