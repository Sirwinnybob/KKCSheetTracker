package com.kkc.sheettracker.ui.jobs

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import com.kkc.sheettracker.data.models.SpecialtyJobCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedJobsScreenTest {
    @Test
    fun specialtyCardsToUnifiedModels_keepsEverySpecialtyScanCardWhenMetadataIsPartial() {
        val scannedCards = listOf(
            SpecialtyJobCard(folderName = "1001 - Viewed", jobNumber = "1001", jobName = "Viewed"),
            SpecialtyJobCard(folderName = "1002 - Other", jobNumber = "1002", jobName = "Other"),
        )

        val models = specialtyCardsToUnifiedModels(
            specialtyCards = scannedCards,
            onJobClick = {},
        )

        assertEquals(
            listOf("1001 - Viewed", "1002 - Other"),
            models.map { it.folderName }
        )
    }

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
    fun deliveryScheduleBinding_forwardsSuppliedFridayAmJobToBannerAndDialogInputs() {
        val supplied = DeliverySchedule(
            slots = mapOf(
                "friday_am" to DeliverySlot(jobs = listOf(DeliveryJob(jobNumber = "FRI-123")))
            )
        )

        val binding = UnifiedJobsDeliveryScheduleBinding(supplied) {}

        assertSame(supplied, binding.bannerSchedule)
        assertSame(supplied, binding.dialogSchedule)
        assertEquals("FRI-123", binding.bannerSchedule.slot("friday", "am").jobs.single().jobNumber)
        assertEquals("FRI-123", binding.dialogSchedule.slot("friday", "am").jobs.single().jobNumber)
    }

    @Test
    fun deliveryScheduleBinding_forwardsCanonicalDirectEditResponseToOwner() {
        val canonical = DeliverySchedule(
            slots = mapOf(
                "friday_am" to DeliverySlot(jobs = listOf(DeliveryJob(jobNumber = "CANONICAL-456")))
            )
        )
        var received: DeliverySchedule? = null
        val binding = UnifiedJobsDeliveryScheduleBinding(canonical) { received = it }

        assertTrue(binding.applyCanonicalResponse(canonical))

        assertSame(canonical, received)
    }

    @Test
    fun deliveryScheduleBinding_doesNotNotifyOwnerWhenDirectEditResponseMissing() {
        var callbackCount = 0
        val binding = UnifiedJobsDeliveryScheduleBinding(DeliverySchedule()) { callbackCount += 1 }

        assertFalse(binding.applyCanonicalResponse(null))

        assertEquals(0, callbackCount)
    }
}
