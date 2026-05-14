package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.StatusCounts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentInProgressEligibilityTest {

    @Test
    fun viewedOnlyMaterialIsNotEligibleForRecentInProgress() {
        val counts = StatusCounts(
            total = 4,
            complete = 0,
            bad = 0,
            skipped = 0,
            notStarted = 4
        )

        assertFalse(isRecentInProgressMaterial(counts))
    }

    @Test
    fun partiallyCompleteMaterialIsEligibleForRecentInProgress() {
        val counts = StatusCounts(
            total = 4,
            complete = 1,
            bad = 0,
            skipped = 0,
            notStarted = 3
        )

        assertTrue(isRecentInProgressMaterial(counts))
    }
}
