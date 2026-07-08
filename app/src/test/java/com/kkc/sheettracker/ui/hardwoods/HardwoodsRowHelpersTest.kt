package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.unit.dp

class HardwoodsRowHelpersTest {
    @Test
    fun cutlistDimensionDisplay_omitsWidthSeparatorForLengthOnlyRows() {
        assertEquals(
            "36",
            cutlistDimensionDisplay(HardwoodCutlistRow(width = "", length = "36"))
        )
        assertEquals(
            "2 x 36",
            cutlistDimensionDisplay(HardwoodCutlistRow(width = "2", length = "36"))
        )
    }

    // normalizeWidthForGrouping is the single width normalizer shared between the width-color-band
    // map builder (HardwoodsWorkspaceScreen) and ClassicCutListTable's per-row lookup. A regression
    // here (e.g. re-forking a divergent copy in ClassicCutListTable.kt) would silently break color
    // band lookups for any width format this doesn't cover identically on both sides.
    @Test
    fun normalizeWidthForGrouping_treatsEquivalentDecimalAndPlainFractionWidthsAsSameKey() {
        assertEquals(
            normalizeWidthForGrouping("0.5\""),
            normalizeWidthForGrouping("1/2\"")
        )
    }

    @Test
    fun normalizeWidthForGrouping_handlesDashSeparatedMixedFraction() {
        assertEquals(
            normalizeWidthForGrouping("3.5"),
            normalizeWidthForGrouping("3-1/2")
        )
    }

    @Test
    fun normalizeWidthForGrouping_handlesSpaceSeparatedMixedFraction() {
        assertEquals(
            normalizeWidthForGrouping("2.75"),
            normalizeWidthForGrouping("2 3/4")
        )
    }

    @Test
    fun normalizeWidthForGrouping_fallsBackToLowercasedTrimmedTextForNonNumeric() {
        assertEquals(
            "unassigned",
            normalizeWidthForGrouping(" Unassigned ")
        )
    }

    @Test
    fun hardwoodsTallyHoldTarget_completesOnlyWhenIncrementCanAdvance() {
        assertEquals(
            HardwoodsTallyHoldTarget.COMPLETE,
            hardwoodsTallyHoldTarget(isSkipped = false, done = 1, total = 3, isIncrement = true)
        )
        assertEquals(
            HardwoodsTallyHoldTarget.NONE,
            hardwoodsTallyHoldTarget(isSkipped = false, done = 3, total = 3, isIncrement = true)
        )
        assertEquals(
            HardwoodsTallyHoldTarget.NONE,
            hardwoodsTallyHoldTarget(isSkipped = true, done = 1, total = 3, isIncrement = true)
        )
    }

    @Test
    fun hardwoodsTallyHoldTarget_zeroesOnlyWhenDecrementCanRetreat() {
        assertEquals(
            HardwoodsTallyHoldTarget.ZERO,
            hardwoodsTallyHoldTarget(isSkipped = false, done = 2, total = 3, isIncrement = false)
        )
        assertEquals(
            HardwoodsTallyHoldTarget.NONE,
            hardwoodsTallyHoldTarget(isSkipped = false, done = 0, total = 3, isIncrement = false)
        )
        assertEquals(
            HardwoodsTallyHoldTarget.NONE,
            hardwoodsTallyHoldTarget(isSkipped = true, done = 2, total = 3, isIncrement = false)
        )
    }

    @Test
    fun hardwoodsListBottomScrollPadding_clearsFloatingAppScaffold() {
        assertEquals(200.dp, hardwoodsListBottomScrollPadding())
    }

    @Test
    fun hardwoodsTallyButtonSize_staysCompactInsideListRows() {
        assertEquals(32.dp, hardwoodsTallyButtonSize())
    }
}
