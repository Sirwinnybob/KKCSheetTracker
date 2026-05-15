package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.BoardStockRow
import com.kkc.sheettracker.data.models.BoardStockSource
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardStockUiSupportTest {

    @Test
    fun groupsBoardStockRowsBySourceThenMaterial() {
        val rows = listOf(
            BoardStockRow(material = "Maple", width = "2", normalizedWidth = 2.0, source = BoardStockSource.FRAME),
            BoardStockRow(material = "Maple", width = "1.5", normalizedWidth = 1.5, source = BoardStockSource.NAILER),
            BoardStockRow(material = "Oak", width = "3", normalizedWidth = 3.0, source = BoardStockSource.FRAME),
            BoardStockRow(material = "Walnut", width = "2", normalizedWidth = 2.0, source = BoardStockSource.DOOR),
            BoardStockRow(material = "Custom", width = "1", normalizedWidth = 1.0, source = BoardStockSource.MANUAL)
        )

        val sections = buildBoardStockSourceSections(rows)

        assertEquals(
            listOf(BoardStockSource.FRAME, BoardStockSource.NAILER, BoardStockSource.DOOR, BoardStockSource.MANUAL),
            sections.map { it.source }
        )
        assertEquals(listOf("Maple", "Oak"), sections[0].materials.map { it.material })
        assertEquals(listOf("Maple"), sections[1].materials.map { it.material })
        assertEquals(listOf("Walnut"), sections[2].materials.map { it.material })
        assertEquals(listOf("Custom"), sections[3].materials.map { it.material })
    }

    @Test
    fun keepsSameMaterialInSeparateSourceGroups() {
        val rows = listOf(
            BoardStockRow(material = "Maple", width = "2", normalizedWidth = 2.0, source = BoardStockSource.FRAME),
            BoardStockRow(material = "Maple", width = "2", normalizedWidth = 2.0, source = BoardStockSource.NAILER)
        )

        val sections = buildBoardStockSourceSections(rows)

        assertEquals(2, sections.size)
        assertEquals(BoardStockSource.FRAME, sections[0].source)
        assertEquals("Maple", sections[0].materials.single().material)
        assertEquals(1, sections[0].materials.single().rows.size)
        assertEquals(BoardStockSource.NAILER, sections[1].source)
        assertEquals("Maple", sections[1].materials.single().material)
        assertEquals(1, sections[1].materials.single().rows.size)
    }

    @Test
    fun mapsParentLabelsToRequestedRipListNames() {
        val rows = listOf(
            BoardStockRow(material = "Maple", width = "2", normalizedWidth = 2.0, source = BoardStockSource.FRAME),
            BoardStockRow(material = "Oak", width = "2", normalizedWidth = 2.0, source = BoardStockSource.NAILER),
            BoardStockRow(material = "Walnut", width = "2", normalizedWidth = 2.0, source = BoardStockSource.DOOR),
            BoardStockRow(material = "Custom", width = "2", normalizedWidth = 2.0, source = BoardStockSource.MANUAL)
        )

        val sections = buildBoardStockSourceSections(rows)

        assertEquals(
            listOf("Face-Frame Rip List", "Nailer Rip List", "Door Rip List", "Manual Rips"),
            sections.map { it.title }
        )
    }
}
