package com.kkc.sheettracker.ui.components.doorcutlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorPanelCutListLayoutTest {

    /** Fixed advance: half the point size per character. */
    private val measurer = object : TextMeasurer {
        override fun width(text: String, sizePt: Float, bold: Boolean): Float = text.length * sizePt * 0.5f
    }

    private fun rows(n: Int, width: (Int) -> String = { "10.0" }, cabinet: String = "12", room: String? = null) =
        List(n) { i -> CutListRow(1, "Door Flat Panel 1A", width(i), "20.0", cabinet, room) }

    private fun model(
        vararg sections: CutListMaterialSection,
        roomTags: Boolean = false,
        rooms: List<CutListRoom> = emptyList(),
        filtered: List<String> = emptyList(),
    ) = DoorPanelCutListModel(
        jobTitle = "669 - WIECHERT 3146 NW CROSSINGS",
        dateText = "1 October, 2026",
        roomTags = roomTags,
        filteredRoomNames = filtered,
        rooms = rooms,
        materials = sections.toList(),
    )

    private fun section(name: String, rows: List<CutListRow>) = CutListMaterialSection(name, "Sheet", rows)

    @Test
    fun `column sets share the same table width`() {
        val std = cutListColumns(false).sumOf { it.widthPt.toDouble() }
        val tags = cutListColumns(true).sumOf { it.widthPt.toDouble() }
        assertEquals(CutListGeometry.TABLE_WIDTH.toDouble(), std, 0.01)
        assertEquals(std, tags, 0.01)
        assertEquals(ColumnKey.ROOM, cutListColumns(true).last().key)
    }

    @Test
    fun `wrapText splits on spaces within width`() {
        // 8.5pt → 4.25pt per char; 40pt fits 9 chars.
        assertEquals(listOf("17 (2),", "18 (2),", "19 (4),", "20 (2)"), wrapText("17 (2), 18 (2), 19 (4), 20 (2)", 40f, 8.5f, false, measurer))
        assertEquals(listOf(""), wrapText("", 40f, 8.5f, false, measurer))
    }

    @Test
    fun `header band text includes mode and filtered rooms`() {
        assertEquals(
            "669 - WIECHERT 3146 NW CROSSINGS · 1 October, 2026 · Standard",
            headerBandText(model())
        )
        assertEquals(
            "669 - WIECHERT 3146 NW CROSSINGS · 1 October, 2026 · Room Tags · Rooms: KITCHEN, PANTRY",
            headerBandText(model(roomTags = true, filtered = listOf("KITCHEN", "PANTRY")))
        )
    }

    @Test
    fun `table header repeats on continuation pages`() {
        val pages = layoutDoorPanelCutList(model(section("1/4 MDF", rows(90))), measurer)
        assertTrue(pages.size >= 2)
        pages.drop(1).forEach { page ->
            val first = page.blocks.first()
            assertTrue(first is TableHeaderBlock && first.continued)
        }
        assertEquals(90, pages.sumOf { p -> p.blocks.count { it is RowBlock } })
    }

    @Test
    fun `material header is never orphaned and rows never cross the content bottom`() {
        for (firstCount in 20..45) {
            val pages = layoutDoorPanelCutList(
                model(section("A", rows(firstCount)), section("B", rows(10))), measurer
            )
            pages.forEach { page ->
                page.blocks.forEachIndexed { i, block ->
                    if (block is MaterialHeaderBlock) {
                        val after = page.blocks.drop(i + 1)
                        assertTrue("firstCount=$firstCount", after.firstOrNull() is TableHeaderBlock)
                        assertTrue("firstCount=$firstCount", after.drop(1).take(2).all { it is RowBlock })
                    }
                    if (block is RowBlock) {
                        assertTrue(block.top + block.height <= CutListGeometry.CONTENT_BOTTOM + 0.01f)
                    }
                }
            }
        }
    }

    @Test
    fun `long cabinet text wraps and grows the row`() {
        val long = "17 (2), 18 (2), 19 (4), 20 (2), 21 (2), 22 (2), 23 (2), 24 (2), 25 (2), 26 (2)"
        val pages = layoutDoorPanelCutList(model(section("A", rows(1, cabinet = long))), measurer)
        val row = pages.single().blocks.filterIsInstance<RowBlock>().single()
        assertTrue(row.cells.getValue(ColumnKey.CABINET).size > 1)
        assertTrue(row.height > CutListGeometry.ROW_MIN_HEIGHT)
    }

    @Test
    fun `width bands cycle per run and advance at each new table`() {
        val widths = listOf("26.0", "26.0", "15.8", "13.4", "13.4")
        val pages = layoutDoorPanelCutList(
            model(section("A", rows(5, width = { widths[it] })), section("B", rows(1, width = { "13.4" }))),
            measurer
        )
        val bands = pages.flatMap { it.blocks }.filterIsInstance<RowBlock>().map { it.widthBandColor }
        val w = CutListColors.WIDTH_BANDS
        assertEquals(listOf(w[0], w[0], w[1], w[2], w[2], w[3]), bands)
    }

    @Test
    fun `room tags rows have room color and no width band`() {
        val kitchen = CutListRoom("Room #1 (KITCHEN)", "KITCHEN", 0xFFF6C85F.toInt())
        val pages = layoutDoorPanelCutList(
            model(
                section("A", rows(2, room = "Room #1 (KITCHEN)") + rows(1, room = "Unassigned")),
                roomTags = true,
                rooms = listOf(kitchen),
            ),
            measurer
        )
        val rowBlocks = pages.flatMap { it.blocks }.filterIsInstance<RowBlock>()
        rowBlocks.forEach { assertNull(it.widthBandColor) }
        assertEquals(listOf(kitchen.color, kitchen.color, UNASSIGNED_ROOM_COLOR), rowBlocks.map { it.roomColor })
        assertEquals(listOf("KITCHEN"), rowBlocks.first().cells.getValue(ColumnKey.ROOM))
        val legend = pages.first().blocks.filterIsInstance<LegendBlock>().single()
        assertEquals(listOf("KITCHEN"), legend.chips.map { it.label })
    }

    @Test
    fun `rows alternate shading starting shaded and continue across pages`() {
        val pages = layoutDoorPanelCutList(model(section("A", rows(90))), measurer)
        val shading = pages.flatMap { it.blocks }.filterIsInstance<RowBlock>().map { it.shaded }
        assertEquals(List(90) { it % 2 == 0 }, shading)
    }

    @Test
    fun `every page has a numbered footer`() {
        val pages = layoutDoorPanelCutList(model(section("A", rows(90))), measurer)
        pages.forEachIndexed { i, page ->
            val footer = page.blocks.last() as FooterBlock
            assertEquals("669 · Door Cut List · Page ${i + 1} of ${pages.size}", footer.text)
            assertEquals(i + 1, page.pageNumber)
        }
        assertTrue(pages.first().blocks.first() is TitleBlock)
    }
}
