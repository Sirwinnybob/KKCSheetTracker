package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.StatusCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetOrderResolverTest {
    @Test
    fun `pending bad part action is material wide and hidden on mix cards`() {
        assertEquals(true, shouldShowPendingBadPartAction(mixSelection = null, pendingCount = 2))
        assertEquals(false, shouldShowPendingBadPartAction(ViewerMixSelection("MIX A", "MIX A.mix"), pendingCount = 2))
        assertEquals(false, shouldShowPendingBadPartAction(mixSelection = null, pendingCount = 0))
    }

    @Test
    fun `mix status counts include only selected pages and treat a shared page independently`() {
        val statuses = mapOf(
            1 to SheetStatus.NOT_STARTED,
            2 to SheetStatus.COMPLETE,
            3 to SheetStatus.HAS_BAD_PARTS
        )

        assertEquals(
            StatusCounts(total = 2, complete = 1, notStarted = 1),
            statusCountsForPages(listOf(2, 1), statuses)
        )
        assertEquals(
            StatusCounts(total = 2, complete = 2, bad = 1),
            statusCountsForPages(listOf(3, 2), statuses)
        )
    }

}
