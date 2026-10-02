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
            val first = page.blocks[0]
            val second = page.blocks[1]
            assertTrue(first is MaterialHeaderBlock && first.text.endsWith("(continued)"))
            assertEquals("Material: '1/4 MDF'  |  Units: Sheet  (continued)", (first as MaterialHeaderBlock).text)
            assertTrue(second is TableHeaderBlock && second.continued)
        }
        assertEquals(90, pages.sumOf { p -> p.blocks.count { it is RowBlock } })
    }

    @Test
    fun `continued material header is stacked above the continued column header`() {
        val pages = layoutDoorPanelCutList(model(section("1/4 MDF", rows(90))), measurer)
        pages.drop(1).forEach { page ->
            val material = page.blocks[0] as MaterialHeaderBlock
            val table = page.blocks[1] as TableHeaderBlock
            val firstRow = page.blocks[2] as RowBlock
            assertEquals(CutListGeometry.MARGIN_TOP, material.top, 0.001f)
            assertEquals(material.top + material.height, table.top, 0.001f)
            assertEquals(table.top + table.height, firstRow.top, 0.001f)
        }
    }

    @Test
    fun `cabinet counts never wrap away from their cabinet`() {
        val long = "17 (2), 18 (2), 19 (4), 20 (2), 21 (2), 22 (2), 23 (2)"
        val pair = """\S+ \(\d+\)"""
        val valid = Regex("^($pair|\\S+)(, ($pair|\\S+))*,?$")
        listOf(false, true).forEach { roomTags ->
            val cabinetWidth = cutListColumns(roomTags).first { it.key == ColumnKey.CABINET }.widthPt
            // Sweep widths around the real one so every possible break point is exercised.
            val widths = listOf(cabinetWidth - 2 * CutListGeometry.CELL_HPAD) +
                (40..110 step 3).map { it.toFloat() }
            widths.forEach { textWidth ->
                val lines = wrapCabinetForTest(long, textWidth)
                assertTrue("roomTags=$roomTags w=$textWidth $lines", lines.size >= 1)
                lines.forEach { line ->
                    val normal = line.replace(' ', ' ')
                    assertTrue("roomTags=$roomTags w=$textWidth line='$normal'", valid.matches(normal) && !normal.startsWith("("))
                }
            }
            // And through the real layout path with the real column widths.
            val pages = layoutDoorPanelCutList(
                model(section("A", rows(1, cabinet = long)), roomTags = roomTags), measurer
            )
            val row = pages.single().blocks.filterIsInstance<RowBlock>().single()
            val cabinetLines = row.cells.getValue(ColumnKey.CABINET)
            assertTrue("roomTags=$roomTags $cabinetLines", cabinetLines.size > 1)
            cabinetLines.forEach { line ->
                val normal = line.replace(' ', ' ')
                assertTrue("roomTags=$roomTags line='$normal'", valid.matches(normal) && !normal.startsWith("("))
            }
        }
    }

    /** Wraps a cabinet list through the layout at an arbitrary text width, via a custom measurer. */
    private fun wrapCabinetForTest(text: String, textWidth: Float): List<String> {
        // Scale the measurer so the real cabinet column (97pt/107pt) behaves like [textWidth].
        val column = cutListColumns(true).first { it.key == ColumnKey.CABINET }
        val real = column.widthPt - 2 * CutListGeometry.CELL_HPAD
        val scaled = object : TextMeasurer {
            override fun width(text: String, sizePt: Float, bold: Boolean): Float =
                measurer.width(text, sizePt, bold) * real / textWidth
        }
        val pages = layoutDoorPanelCutList(
            model(section("A", rows(1, cabinet = text)), roomTags = true), scaled
        )
        return pages.flatMap { it.blocks }.filterIsInstance<RowBlock>().single().cells.getValue(ColumnKey.CABINET)
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
                        val header = after.firstOrNull()
                        assertTrue("firstCount=$firstCount", header is TableHeaderBlock)
                        if ((header as TableHeaderBlock).continued) {
                            // A "(continued)" header exists because a row followed: at least one row.
                            assertTrue("firstCount=$firstCount", block.text.endsWith("(continued)"))
                            assertTrue("firstCount=$firstCount", after.getOrNull(1) is RowBlock)
                        } else {
                            assertTrue("firstCount=$firstCount", after.drop(1).take(2).all { it is RowBlock })
                        }
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
    fun `ellipsize keeps short text and cuts long text with an ellipsis`() {
        // 10pt → 5pt per char; 30pt fits 6 chars.
        assertEquals("HELLO", ellipsize("HELLO", 30f, 10f, true, measurer))
        assertEquals("ABCDEF", ellipsize("ABCDEF", 30f, 10f, true, measurer))

        val cut = ellipsize("ABCDEFGHIJ", 30f, 10f, true, measurer)
        assertTrue(cut.endsWith("…"))
        assertTrue(measurer.width(cut, 10f, true) <= 30f)
        assertEquals("ABCDE…", cut)
        // Longest such prefix: one more character would no longer fit.
        assertTrue(measurer.width(cut.dropLast(1) + "F…", 10f, true) > 30f)

        // Trailing spaces are trimmed before the ellipsis.
        assertEquals("AB…", ellipsize("AB CDEFGHIJ", 20f, 10f, true, measurer))

        // Too narrow for even the ellipsis.
        assertEquals("…", ellipsize("ABCDEFGHIJ", 4f, 10f, true, measurer))
        assertEquals("", ellipsize("", 30f, 10f, true, measurer))
    }

    @Test
    fun `long room names stay on one line with an ellipsis`() {
        val longName = "SHOWROOM 2 NORTH WALL EXTRA LONG NAME"
        val key = "Room #2 ($longName)"
        val rooms = listOf(CutListRoom(key, longName, 0xFFF6C85F.toInt()))
        val pages = layoutDoorPanelCutList(
            model(section("A", rows(1, room = key)), roomTags = true, rooms = rooms),
            measurer
        )
        val row = pages.flatMap { it.blocks }.filterIsInstance<RowBlock>().single()
        val roomLines = row.cells.getValue(ColumnKey.ROOM)
        assertEquals(1, roomLines.size)
        assertTrue(roomLines.single().endsWith("…"))
        assertEquals(CutListGeometry.ROW_MIN_HEIGHT, row.height, 0.001f)
        val legend = pages.first().blocks.filterIsInstance<LegendBlock>().single()
        assertTrue(legend.chips.single().label.endsWith("…"))
    }

    @Test
    fun `single line rows use the taller minimum height`() {
        val pages = layoutDoorPanelCutList(model(section("A", rows(1))), measurer)
        val row = pages.single().blocks.filterIsInstance<RowBlock>().single()
        assertEquals(22f, row.height, 0.001f)
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
