package com.kkc.sheettracker.ui.components.doorcutlist

import com.kkc.sheettracker.data.models.AssemblySourceDocumentIndex
import com.kkc.sheettracker.data.models.AssemblyVirtualCombinedIndex
import com.kkc.sheettracker.data.models.CabinetPageDetail
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.models.CabinetSheetIndexDocuments
import com.kkc.sheettracker.data.models.ReferenceDocumentIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorPanelCutListDataTest {

    private fun page(room: String?, vararg cabs: String) =
        CabinetPageDetail(cabinets = cabs.toList(), room = room)

    private fun pages(vararg details: CabinetPageDetail): Map<String, CabinetPageDetail> =
        details.mapIndexed { i, d -> (i + 1).toString() to d }.toMap()

    // ---- parseCabinetText / formatCabinets ----

    @Test
    fun `parseCabinetText reads counts and bare cabinets`() {
        assertEquals(
            listOf(CabinetCount("9", 3), CabinetCount("11", 2), CabinetCount("13", 1)),
            parseCabinetText("9 (3), 11 (2), 13")
        )
    }

    @Test
    fun `parseCabinetText handles compact and lettered cabinets`() {
        assertEquals(
            listOf(CabinetCount("9", 3), CabinetCount("15A", 2), CabinetCount("20", 1)),
            parseCabinetText("9(3), 15A (2), 20,")
        )
    }

    @Test
    fun `parseCabinetText returns empty for blank input`() {
        assertEquals(emptyList<CabinetCount>(), parseCabinetText(""))
        assertEquals(emptyList<CabinetCount>(), parseCabinetText(" , "))
    }

    @Test
    fun `formatCabinets omits count of one`() {
        assertEquals(
            "9 (3), 20",
            formatCabinets(listOf(CabinetCount("9", 3), CabinetCount("20", 1)))
        )
    }

    // ---- room naming / ordering ----

    @Test
    fun `roomDisplayName extracts parenthesized name`() {
        assertEquals("VANITIES", roomDisplayName("Room #3 (VANITIES)"))
        assertEquals("UTILITY - BENCH", roomDisplayName("Room #4 (UTILITY - BENCH)"))
    }

    @Test
    fun `roomDisplayName falls back to raw label`() {
        assertEquals("KITCHEN", roomDisplayName("KITCHEN"))
        assertEquals(UNASSIGNED_ROOM_KEY, roomDisplayName(UNASSIGNED_ROOM_KEY))
    }

    @Test
    fun `roomKeyComparator orders numbered then named then unassigned`() {
        val sorted = listOf(
            UNASSIGNED_ROOM_KEY, "Room #10 (X)", "KITCHEN", "Room #2 (B)", "Room #1 (A)"
        ).sortedWith(roomKeyComparator)
        assertEquals(
            listOf("Room #1 (A)", "Room #2 (B)", "Room #10 (X)", "KITCHEN", UNASSIGNED_ROOM_KEY),
            sorted
        )
    }

    // ---- buildCabinetRoomMap ----

    @Test
    fun `buildCabinetRoomMap returns empty for null index`() {
        assertTrue(buildCabinetRoomMap(null).isEmpty())
    }

    @Test
    fun `buildCabinetRoomMap prefers virtualCombined then sources then assembly then plans`() {
        val index = CabinetSheetIndex(
            documents = CabinetSheetIndexDocuments(
                assembly = ReferenceDocumentIndex(
                    // Top-level = FF sheets only, and (deliberately) a conflicting room for cab 1.
                    pageDetails = pages(page("Room #9 (WRONG)", "1"), page("Room #1 (KITCHEN)", "2")),
                    sources = listOf(
                        AssemblySourceDocumentIndex(pageDetails = pages(page("Room #5 (CLOSET)", "56"))),
                    ),
                    virtualCombined = AssemblyVirtualCombinedIndex(
                        pageDetails = pages(page("Room #1 (KITCHEN)", "1"), page("Room #5 (CLOSET)", "57"))
                    )
                ),
                plansElevations = ReferenceDocumentIndex(
                    pageDetails = pages(
                        page("Room #5 (CLOSET)", "63", "64"),
                        page("Room #8 (WRONG)", "2")
                    )
                )
            )
        )
        val map = buildCabinetRoomMap(index)
        assertEquals("Room #1 (KITCHEN)", map["1"])   // virtualCombined beats top-level
        assertEquals("Room #5 (CLOSET)", map["57"])   // virtualCombined
        assertEquals("Room #5 (CLOSET)", map["56"])   // sources
        assertEquals("Room #1 (KITCHEN)", map["2"])   // top-level beats plans
        assertEquals("Room #5 (CLOSET)", map["63"])   // plans fallback
        assertEquals("Room #5 (CLOSET)", map["64"])
    }

    @Test
    fun `buildCabinetRoomMap skips pages with blank room`() {
        val index = CabinetSheetIndex(
            documents = CabinetSheetIndexDocuments(
                assembly = ReferenceDocumentIndex(pageDetails = pages(page("  ", "70"), page(null, "71"))),
                plansElevations = ReferenceDocumentIndex(pageDetails = pages(page("Room #5 (CLOSET)", "70", "71")))
            )
        )
        val map = buildCabinetRoomMap(index)
        assertEquals("Room #5 (CLOSET)", map["70"])
        assertEquals("Room #5 (CLOSET)", map["71"])
    }
}
