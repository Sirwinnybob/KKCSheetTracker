package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetRipTallyTest {
    @Test
    fun resolve_usesLegacyOnlyWithoutTally() {
        assertEquals(SheetRipTallyState(3, 3), resolveSheetRipTallyState(null, true, 3))
        assertEquals(SheetRipTallyState(0, 3), resolveSheetRipTallyState(0, true, 3))
    }

    @Test
    fun resolve_clampsToZeroAndTarget() {
        assertEquals(SheetRipTallyState(0, 2), resolveSheetRipTallyState(-1, false, 2))
        assertEquals(SheetRipTallyState(2, 2), resolveSheetRipTallyState(9, false, 2))
    }

    @Test
    fun isComplete_requiresPositiveTargetAndReachedTarget() {
        assertEquals(false, SheetRipTallyState(0, 0).isComplete)
        assertEquals(false, SheetRipTallyState(1, 2).isComplete)
        assertEquals(true, SheetRipTallyState(2, 2).isComplete)
    }
}
