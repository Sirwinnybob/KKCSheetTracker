package com.kkc.sheettracker.ui.jobs

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedJobsScreenTest {
    @Test
    fun backgroundWorkRunsOnlyForTheActiveTab() {
        assertTrue(shouldRunUnifiedJobsBackgroundWork(active = true))
        assertFalse(shouldRunUnifiedJobsBackgroundWork(active = false))
    }

    @Test
    fun labelLoadingRunsOnlyForTheActiveTab() {
        assertTrue(shouldLoadUnifiedJobsLabels(active = true))
        assertFalse(shouldLoadUnifiedJobsLabels(active = false))
    }

    @Test
    fun deliveryScheduleForJobsScreen_preservesSuppliedFridayAmJob() {
        val supplied = DeliverySchedule(
            slots = mapOf(
                "friday_am" to DeliverySlot(jobs = listOf(DeliveryJob(jobNumber = "FRI-123")))
            )
        )

        val presentation = deliveryScheduleForJobsScreen(supplied)

        assertSame(supplied, presentation)
        assertEquals("FRI-123", presentation.slot("friday", "am").jobs.single().jobNumber)
    }

    @Test
    fun applyCanonicalDeliverySchedule_forwardsImmediateAdminResponseToOwner() {
        val canonical = DeliverySchedule(
            slots = mapOf(
                "friday_am" to DeliverySlot(jobs = listOf(DeliveryJob(jobNumber = "CANONICAL-456")))
            )
        )
        var received: DeliverySchedule? = null

        applyCanonicalDeliverySchedule(canonical) { received = it }

        assertSame(canonical, received)
    }
}
