package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.AdminBoardStockItem
import com.kkc.sheettracker.data.models.BoardStockRow
import com.kkc.sheettracker.data.models.BoardStockSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun hardwoodsLazyRowEntries_keepEveryLargeSectionRowAsItsOwnListEntry() {
        val rows = (1..95).map { index -> HardwoodCutlistRow(rowId = "ROW-$index") }

        val entries = hardwoodsLazyRowEntries(
            docTypeName = "FACE_FRAME_CUT_LIST",
            sectionKey = "3/4 Solid White Oak",
            rows = rows
        )

        assertEquals(95, entries.size)
        assertEquals(95, entries.map { it.key }.toSet().size)
        assertEquals(rows, entries.map { it.row })
    }

    @Test
    fun boardStockLazyRowEntries_keepEvery95RowMaterialGroupAsItsOwnListEntry() {
        val rows = (1..95).map { index ->
            BoardStockRow(
                stableKey = "RIP-$index",
                material = "White Oak",
                source = BoardStockSource.FRAME
            )
        }

        val entries = boardStockLazyRowEntries(
            sourceKey = "FRAME",
            material = "White Oak",
            rows = rows
        )

        assertEquals(95, entries.size)
        assertEquals(95, entries.map { it.key }.toSet().size)
        assertEquals(rows, entries.map { it.row })
    }

    @Test
    fun adminBoardStockLazyRowEntries_keepEvery95RowMaterialGroupAsItsOwnListEntry() {
        val items = (1..95).map { index ->
            AdminBoardStockItem(
                id = "ADMIN-$index",
                material = "White Oak",
                name = "Part $index",
                feet = 10.0
            )
        }

        val entries = adminBoardStockLazyRowEntries(
            material = "White Oak",
            items = items
        )

        assertEquals(95, entries.size)
        assertEquals(95, entries.map { it.key }.toSet().size)
        assertEquals(items, entries.map { it.item })
    }

    @Test
    fun hardwoodsRipRouting_keepsCrownInBothUnitsButMakesSheetReadOnly() {
        val sheetCrown = AdminBoardStockItem("c1", "Maple", "Crown", 18.0, mode = "sHeEt", type = "CrOwN")
        val boardCrown = AdminBoardStockItem("c2", "Maple", "Crown", 18.0, mode = "Bd_Ft", moldingId = "Crown:151")

        assertTrue(isVisibleInHardwoodsRipList(sheetCrown, isSawRipEntry = false))
        assertTrue(isVisibleInHardwoodsRipList(boardCrown, isSawRipEntry = false))
        assertEquals("Sheet", hardwoodsBoardStockUnitLabel(sheetCrown))
        assertEquals("BD FT", hardwoodsBoardStockUnitLabel(boardCrown))
        assertFalse(showsHardwoodsBoardStockTallyControls(sheetCrown, isSawRipEntry = false))
        assertTrue(showsHardwoodsBoardStockTallyControls(boardCrown, isSawRipEntry = false))
    }

    @Test
    fun materialSkipState_appliesOnlyToBdFtRowsInMixedUnitMaterialGroup() {
        val sheetCrown = AdminBoardStockItem("sheet", "Maple", "Crown", 18.0, mode = "sheet", type = "crown")
        val boardCrown = AdminBoardStockItem("board", "Maple", "Crown", 18.0, mode = "bd_ft", type = "crown")

        assertFalse(isHardwoodsBoardStockMaterialSkipApplied(sheetCrown, materialSkipped = true))
        assertTrue(isHardwoodsBoardStockMaterialSkipApplied(boardCrown, materialSkipped = true))
    }

    @Test
    fun materialSkipHeader_ignoresSheetOnlyGroupsButKeepsBdFtAndMixedGroupsSkipped() {
        val sheetCrown = AdminBoardStockItem("sheet", "Maple", "Crown", 18.0, mode = "sheet", type = "crown")
        val boardCrown = AdminBoardStockItem("board", "Maple", "Crown", 18.0, mode = "bd_ft", type = "crown")

        assertFalse(hardwoodsEffectiveMaterialSkipped(listOf(sheetCrown), materialSkipped = true))
        assertTrue(hardwoodsEffectiveMaterialSkipped(listOf(boardCrown), materialSkipped = true))
        assertTrue(hardwoodsEffectiveMaterialSkipped(listOf(sheetCrown, boardCrown), materialSkipped = true))
    }

    @Test
    fun sheetTallyAndSkipPolicies_areModeAndRouteAware() {
        val sheetCrown = AdminBoardStockItem("sheet", "Maple", "Crown", 12.0, mode = "sHeEt", type = "crown")
        val boardCrown = AdminBoardStockItem("board", "Maple", "Crown", 18.0, mode = "bd_ft", type = "crown")

        assertTrue(showsHardwoodsBoardStockTallyControls(sheetCrown, isSawRipEntry = true))
        assertFalse(showsHardwoodsBoardStockTallyControls(sheetCrown, isSawRipEntry = false))
        assertTrue(showsHardwoodsBoardStockTallyControls(boardCrown, isSawRipEntry = false))
        assertFalse(allowsHardwoodsBoardStockSkip(sheetCrown))
        assertTrue(allowsHardwoodsBoardStockSkip(boardCrown))
assertEquals("2x 9ft Board Rips", hardwoodsBoardStockRequirementLabel(2, 9, 12.0))
        assertEquals("2x 12ft Board Rips", hardwoodsBoardStockRequirementLabel(2, 12, 18.0))
        assertFalse(hardwoodsEffectiveMaterialSkipped(listOf(sheetCrown), materialSkipped = true))
    }

    @Test
    fun hardwoodsBoardStockUnitLabel_defaultsNonSheetCrownModesToBdFt() {
        val blankModeCrown = AdminBoardStockItem("blank", "Maple", "Crown", 18.0, mode = "", type = "crown")
        val unknownModeCrown = AdminBoardStockItem("unknown", "Maple", "Crown", 18.0, mode = "linear_feet", type = "crown")

        assertEquals("BD FT", hardwoodsBoardStockUnitLabel(blankModeCrown))
        assertEquals("BD FT", hardwoodsBoardStockUnitLabel(unknownModeCrown))
    }
}
