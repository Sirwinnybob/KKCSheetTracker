package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.buildDeliveryWeek
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DeliveryScheduleDialogLogicTest {
    private val mon = LocalDate.of(2026, 10, 12)
    private val wed = LocalDate.of(2026, 10, 14)
    private val sat = LocalDate.of(2026, 10, 17)
    private val nextMon = LocalDate.of(2026, 10, 19)

    private fun jobs(prefix: String, n: Int) =
        (1..n).map { DeliveryJob("$prefix$it", "D$it", folderName = "$prefix$it - D$it") }

    private fun v2(vararg slots: Pair<String, List<DeliveryJob>>) = DeliverySchedule(
        datedSlots = slots.toMap().mapValues { DeliverySlot(it.value) },
        weekOf = mon,
        isDated = true
    )

    private fun v1(vararg slots: Pair<String, List<DeliveryJob>>) =
        DeliverySchedule(slots = slots.toMap().mapValues { DeliverySlot(it.value) })

    private fun dayOf(schedule: DeliverySchedule, today: LocalDate, date: LocalDate) =
        buildDeliveryWeek(schedule, date, today, includeEmptyWeekend = true).days.first { it.date == date }

    // ---- C8: deliveryDropAllowed ----

    @Test
    fun emptyDayAllowsAPoolJob() {
        val s = v2()
        assertEquals(DropDecision.Allowed, deliveryDropAllowed(s, dayOf(s, wed, wed), "2026-10-14_am", null, true))
    }

    @Test
    fun sixthJobIsAllowedAndSeventhIsRefusedAsDayFull() {
        val five = v2("2026-10-14_am" to jobs("a", 3), "2026-10-14_pm" to jobs("b", 2))
        assertEquals(DropDecision.Allowed, deliveryDropAllowed(five, dayOf(five, wed, wed), "2026-10-14_pm", null, true))
        val six = v2("2026-10-14_am" to jobs("a", 3), "2026-10-14_pm" to jobs("b", 3))
        assertEquals(DropDecision.DayFull, deliveryDropAllowed(six, dayOf(six, wed, wed), "2026-10-14_am", null, true))
        assertEquals(DropDecision.DayFull, deliveryDropAllowed(six, dayOf(six, wed, wed), "2026-10-14_pm", null, true))
    }

    @Test
    fun movingWithinAFullDayIsAllowedButAnotherDaysJobIsNot() {
        val six = v2("2026-10-14_am" to jobs("a", 3), "2026-10-14_pm" to jobs("b", 3))
        val day = dayOf(six, wed, wed)
        // AM -> PM: the moving job leaves the day's count alone.
        assertEquals(DropDecision.Allowed, deliveryDropAllowed(six, day, "2026-10-14_pm", "2026-10-14_am", true))
        // From another day's slot it is a new arrival.
        assertEquals(DropDecision.DayFull, deliveryDropAllowed(six, day, "2026-10-14_pm", "2026-10-15_am", true))
        // With the job known, it is recognised even when the source key is unknown (pool job already on the day).
        assertEquals(DropDecision.Allowed, deliveryDropAllowed(six, day, "2026-10-14_pm", null, true, job = jobs("a", 1).single()))
    }

    @Test
    fun daysMoreThanFourteenDaysBackArePastCutoffBeforeAnyCountCheck() {
        val old = LocalDate.of(2026, 9, 28)
        val s = v2("2026-09-28_am" to jobs("a", 6))
        val day = dayOf(s, wed, old)
        assertEquals(DropDecision.PastCutoff, deliveryDropAllowed(s, day, "2026-09-28_pm", null, true))
        // The first placeable day (cutoff is Sep 30) is judged on its own count instead.
        assertEquals(DropDecision.Allowed, deliveryDropAllowed(s, dayOf(s, wed, LocalDate.of(2026, 9, 30)), "2026-09-30_am", null, true))
    }

    // ---- C6: the v1 3-per-half-day guard ----

    @Test
    fun legacyHalfDayHoldsAtMostThree() {
        val s = v1("monday_am" to jobs("a", 3), "monday_pm" to jobs("b", 1))
        val day = dayOf(s, mon, mon)
        assertEquals(DropDecision.HalfDayFull, deliveryDropAllowed(s, day, "monday_am", null, false))
        assertEquals(DropDecision.HalfDayFull, deliveryDropAllowed(s, day, "monday_am", "monday_pm", false))
        assertEquals(DropDecision.Allowed, deliveryDropAllowed(s, day, "monday_pm", "monday_am", false))
        assertEquals(DropDecision.Allowed, deliveryDropAllowed(s, day, "monday_pm", null, false))
    }

    @Test
    fun datedHalfDayAllowsFourthFifthAndSixthJobs() {
        val dayKey = "2026-10-14_am"
        for (currentCount in 3..5) {
            val s = v2(dayKey to jobs("a", currentCount))
            assertEquals(
                "Dated half-day should accept job ${currentCount + 1}",
                DropDecision.Allowed,
                deliveryDropAllowed(s, dayOf(s, wed, wed), dayKey, null, true)
            )
        }
    }

    @Test
    fun legacyFourthJobInHalfDayIsRejected() {
        val s = v1("monday_am" to jobs("a", 3))
        assertEquals(DropDecision.HalfDayFull, deliveryDropAllowed(s, dayOf(s, mon, mon), "monday_am", null, false))
    }

    @Test
    fun legacyDayLimitStillAppliesAcrossBothHalves() {
        val s = v1("monday_am" to jobs("a", 3), "monday_pm" to jobs("b", 3))
        val day = dayOf(s, mon, mon)
        assertEquals(DropDecision.DayFull, deliveryDropAllowed(s, day, "monday_pm", null, false))
    }

    @Test
    fun rejectedDropsHaveReadableNotices() {
        assertNull(deliveryDropNotice(DropDecision.Allowed))
        assertEquals("That day already has 6 deliveries", deliveryDropNotice(DropDecision.DayFull))
        assertEquals("That half-day already has 3 deliveries", deliveryDropNotice(DropDecision.HalfDayFull))
        assertEquals("Too far in the past", deliveryDropNotice(DropDecision.PastCutoff))
    }

    // ---- C1: selected week ----

    @Test
    fun defaultWeekFollowsTheRolloverButASelectedWeekStaysPut() {
        val before = DeliverySchedule(isDated = true, weekOf = mon)
        val after = DeliverySchedule(isDated = true, weekOf = nextMon)
        // Following the default: it moves at the Saturday rollover.
        assertEquals(mon, resolveDeliveryWeekStart(before, wed, null))
        assertEquals(nextMon, resolveDeliveryWeekStart(after, sat, null))
        // An absolute selection does not.
        assertEquals(nextMon, resolveDeliveryWeekStart(before, wed, nextMon))
        assertEquals(nextMon, resolveDeliveryWeekStart(after, sat, nextMon))
        assertEquals(mon, resolveDeliveryWeekStart(after, sat, mon))
    }

    @Test
    fun selectedWeekIsNormalizedToItsMondayAndIgnoredForV1() {
        val v2 = DeliverySchedule(isDated = true, weekOf = mon)
        assertEquals(nextMon, resolveDeliveryWeekStart(v2, wed, LocalDate.of(2026, 10, 22)))
        // v1 has no navigation: the stale selection is ignored without any state write.
        assertEquals(mon, resolveDeliveryWeekStart(DeliverySchedule(), wed, nextMon))
    }

    @Test
    fun navigatingBackToTheDefaultWeekClearsTheSelection() {
        assertNull(selectDeliveryWeek(mon, defaultWeek = mon))
        assertEquals(nextMon, selectDeliveryWeek(nextMon, defaultWeek = mon))
    }

    // ---- C2 / C10 ----

    @Test
    fun resetConfirmNamesTheDatesItClears() {
        assertEquals("Confirm: clear Mon 10/12 – Fri 10/16", deliveryResetConfirmLabel(mon))
        assertEquals("Confirm: clear Mon 10/19 – Fri 10/23", deliveryResetConfirmLabel(nextMon))
    }

    @Test
    fun editStatusComesFromInFlightPlusQueuedSlots() {
        assertNull(deliveryEditStatusText(0, 0))
        assertEquals("2 edit(s) sending/queued", deliveryEditStatusText(inFlightEdits = 2, pendingSlotCount = 0))
        assertEquals("3 edit(s) sending/queued", deliveryEditStatusText(inFlightEdits = 1, pendingSlotCount = 2))
        // Still counted after the schedule changed underneath: it depends on nothing but its inputs.
        assertEquals("1 edit(s) sending/queued", deliveryEditStatusText(inFlightEdits = 0, pendingSlotCount = 1))
    }
}
