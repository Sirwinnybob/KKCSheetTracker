package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGroupWrapLayoutTest {
    @Test
    fun fittingAsymmetricGroupsKeepTheirNaturalWidths() {
        val allocation = allocateActionGroupWidths(
            leadingNaturalWidth = 132,
            leadingMinWidth = 44,
            trailingNaturalWidth = 76,
            trailingMinWidth = 48,
            availableWidth = 320,
            groupGap = 16
        )

        assertEquals(132, allocation.leadingWidth)
        assertEquals(76, allocation.trailingWidth)
        assertFalse(allocation.stackVertically)
    }

    @Test
    fun overflowRedistributesUnusedShareToLongerGroup() {
        val allocation = allocateActionGroupWidths(
            leadingNaturalWidth = 80,
            leadingMinWidth = 40,
            trailingNaturalWidth = 150,
            trailingMinWidth = 40,
            availableWidth = 216,
            groupGap = 16
        )

        assertEquals(80, allocation.leadingWidth)
        assertEquals(120, allocation.trailingWidth)
        assertFalse(allocation.stackVertically)
    }

    @Test
    fun twoLongGroupsSplitAvailableContentFairly() {
        val allocation = allocateActionGroupWidths(
            leadingNaturalWidth = 240,
            leadingMinWidth = 60,
            trailingNaturalWidth = 200,
            trailingMinWidth = 60,
            availableWidth = 216,
            groupGap = 16
        )

        assertEquals(100, allocation.leadingWidth)
        assertEquals(100, allocation.trailingWidth)
        assertFalse(allocation.stackVertically)
    }

    @Test
    fun emptyGroupDoesNotReserveHalfTheWidthOrTheGroupGap() {
        val allocation = allocateActionGroupWidths(
            leadingNaturalWidth = 0,
            leadingMinWidth = 0,
            trailingNaturalWidth = 150,
            trailingMinWidth = 60,
            availableWidth = 220,
            groupGap = 16
        )

        assertEquals(0, allocation.leadingWidth)
        assertEquals(150, allocation.trailingWidth)
        assertFalse(allocation.stackVertically)
    }

    @Test
    fun groupsStackWhenTheirMinimumWidthsCannotFitBesideEachOther() {
        val allocation = allocateActionGroupWidths(
            leadingNaturalWidth = 110,
            leadingMinWidth = 80,
            trailingNaturalWidth = 100,
            trailingMinWidth = 70,
            availableWidth = 150,
            groupGap = 16
        )

        assertEquals(150, allocation.leadingWidth)
        assertEquals(150, allocation.trailingWidth)
        assertTrue(allocation.stackVertically)
    }
}
