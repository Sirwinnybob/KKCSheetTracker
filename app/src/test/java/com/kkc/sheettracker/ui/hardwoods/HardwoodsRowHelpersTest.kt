package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
