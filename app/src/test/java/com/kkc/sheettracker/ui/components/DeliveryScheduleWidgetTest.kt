package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryScheduleWidgetTest {

    @Test
    fun shouldShowDeliveryScheduleWidget_hidesEmptyScheduleForNonAdmins() {
        assertFalse(shouldShowDeliveryScheduleWidget(DeliverySchedule(), showWhenEmpty = false))
    }

    @Test
    fun shouldShowDeliveryScheduleWidget_showsEmptyScheduleForAdmins() {
        assertTrue(shouldShowDeliveryScheduleWidget(DeliverySchedule(), showWhenEmpty = true))
    }

    @Test
    fun shouldShowDeliveryScheduleWidget_showsPopulatedScheduleForEveryone() {
        val schedule = DeliverySchedule(
            slots = mapOf(
                "monday_am" to DeliverySlot(
                    jobs = listOf(DeliveryJob(jobNumber = "123", description = "Smith"))
                )
            )
        )

        assertTrue(shouldShowDeliveryScheduleWidget(schedule, showWhenEmpty = false))
    }
}
