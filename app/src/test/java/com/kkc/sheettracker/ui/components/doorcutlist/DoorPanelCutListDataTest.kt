package com.kkc.sheettracker.ui.components.doorcutlist

import com.kkc.sheettracker.data.models.AssemblySourceDocumentIndex
import com.kkc.sheettracker.data.models.AssemblyVirtualCombinedIndex
import com.kkc.sheettracker.data.models.CabinetPageDetail
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.models.CabinetSheetIndexDocuments
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.ReferenceDocumentIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- fixtures modelled on 669 / 684 ----

    private val rooms669 = mapOf(
        "20" to "Room #1 (KITCHEN)",
        "23" to "Room #2 (PANTRY)",
        "30" to "Room #3 (VANITIES)",
        "42" to "Room #4 (UTILITY - BENCH)",
        "63" to "Room #5 (CLOSET)",
        "64" to "Room #5 (CLOSET)",
    )

    private fun row(
        ordinal: Int,
        qty: Int,
        cabinetText: String,
        material: String? = "3/4 2s White Oak Rift",
        width: String = "17.125",
        description: String = "Door Slab 1B-L",
        cabinets: List<String> = emptyList(),
    ) = HardwoodCutlistRow(
        rowId = "DOOR_CUT_LIST:1:$ordinal",
        page = 1,
        rowOrdinal = ordinal,
        qty = qty,
        material = material,
        description = description,
        width = width,
        length = "24.125",
        unitType = "SHEETS",
        cabinets = cabinets,
        rawCabinetText = cabinetText,
    )

    private fun source669() = DoorPanelCutListSource(
        jobFolderName = "669 - WIECHERT 3146 NW CROSSINGS",
        rows = listOf(
            resolveRow(row(0, 1, "20", width = "34.375"), rooms669),
            resolveRow(row(1, 2, "20, 42"), rooms669),                       // spans KITCHEN + UTILITY
            resolveRow(row(2, 2, "23, 30", width = "19.125"), rooms669),     // spans PANTRY + VANITIES
            resolveRow(row(3, 2, "63, 64", material = "3/4 DOUBLE FUMED", width = "31.0675"), rooms669),
            resolveRow(row(4, 1, "30", material = "1/4 MDF", width = "9.565"), rooms669),
        )
    )

    // ---- resolveRow ----

    @Test
    fun `single room row keeps CV qty and cabinet text`() {
        val r = resolveRow(row(0, 7, "9 (3), 11 (2), 13 (2)"), mapOf("9" to "R1", "11" to "R1", "13" to "R1"))
        assertEquals(7, r.qty)
        assertEquals("9 (3), 11 (2), 13 (2)", r.cabinetText)
        assertEquals(listOf(RoomGroup("R1", parseCabinetText("9 (3), 11 (2), 13 (2)"), 7)), r.groups)
    }

    @Test
    fun `multi room row splits qty by cabinet counts in room order`() {
        val r = resolveRow(
            row(0, 7, "16 (2), 9 (3), 11 (2)"),
            mapOf("9" to "Room #1 (KITCHEN)", "11" to "Room #1 (KITCHEN)", "16" to "Room #2 (LAUNDRY)")
        )
        assertEquals(
            listOf(
                RoomGroup("Room #1 (KITCHEN)", listOf(CabinetCount("9", 3), CabinetCount("11", 2)), 5),
                RoomGroup("Room #2 (LAUNDRY)", listOf(CabinetCount("16", 2)), 2),
            ),
            r.groups
        )
    }

    @Test
    fun `multi room row whose counts disagree with qty stays whole under unassigned`() {
        val r = resolveRow(row(0, 4, "20, 42"), rooms669)
        assertEquals(
            listOf(RoomGroup(UNASSIGNED_ROOM_KEY, listOf(CabinetCount("20", 1), CabinetCount("42", 1)), 4)),
            r.groups
        )
        assertEquals("20, 42", r.cabinetText)
    }

    @Test
    fun `blank cabinet text falls back to cabinets list`() {
        val r = resolveRow(row(0, 2, "", cabinets = listOf("20", "42")), rooms669)
        assertEquals(listOf("Room #1 (KITCHEN)", "Room #4 (UTILITY - BENCH)"), r.groups.map { it.roomKey })
        assertEquals("20, 42", r.cabinetText)
    }

    @Test
    fun `row with no cabinets is unassigned with original qty`() {
        val r = resolveRow(row(0, 3, ""), rooms669)
        assertEquals(listOf(RoomGroup(UNASSIGNED_ROOM_KEY, emptyList(), 3)), r.groups)
    }

    @Test
    fun `unknown cabinet maps to unassigned`() {
        val r = resolveRow(row(0, 1, "99"), rooms669)
        assertEquals(UNASSIGNED_ROOM_KEY, r.groups.single().roomKey)
    }

    @Test
    fun `null material groups under UNKNOWN_MATERIAL`() {
        val r = resolveRow(row(0, 1, "20", material = null), rooms669)
        assertEquals(UNKNOWN_MATERIAL, r.material)
    }

    // ---- source / selection / options ----

    @Test
    fun `source exposes materials in first appearance order and sorted rooms`() {
        val s = source669()
        assertEquals(listOf("3/4 2s White Oak Rift", "3/4 DOUBLE FUMED", "1/4 MDF"), s.materials)
        assertEquals(
            listOf("Room #1 (KITCHEN)", "Room #2 (PANTRY)", "Room #3 (VANITIES)", "Room #4 (UTILITY - BENCH)", "Room #5 (CLOSET)"),
            s.roomKeys
        )
    }

    @Test
    fun `default selection unchecks MDF materials and checks all rooms`() {
        assertTrue(isDefaultUncheckedMaterial("1/4 MDF"))
        assertTrue(isDefaultUncheckedMaterial("1/4 PG Maple mdf"))
        assertFalse(isDefaultUncheckedMaterial("3/4 2s White Oak Rift"))
        val sel = defaultSelection(source669())
        assertFalse(sel.roomTags)
        assertEquals(setOf("3/4 2s White Oak Rift", "3/4 DOUBLE FUMED"), sel.materials)
        assertEquals(source669().roomKeys.toSet(), sel.rooms)
    }

    @Test
    fun `material counts respect room filter and room counts respect material filter`() {
        val s = source669()
        val sel = defaultSelection(s).copy(rooms = s.roomKeys.toSet() - "Room #4 (UTILITY - BENCH)")
        val mats = materialOptions(s, sel).associateBy { it.material }
        assertEquals(1 + 1 + 2, mats.getValue("3/4 2s White Oak Rift").pieces) // UTILITY share of row 1 removed
        assertTrue(mats.getValue("3/4 2s White Oak Rift").checked)
        assertFalse(mats.getValue("1/4 MDF").checked)

        val roomsOpt = roomOptions(s, sel).associateBy { it.key }
        assertEquals(1, roomsOpt.getValue("Room #3 (VANITIES)").pieces) // 1 from row 2; unchecked MDF row excluded
        assertEquals("VANITIES", roomsOpt.getValue("Room #3 (VANITIES)").displayName)
        assertFalse(roomsOpt.getValue("Room #4 (UTILITY - BENCH)").checked)
    }

    @Test
    fun `room filter hidden for single room job`() {
        val single = DoorPanelCutListSource("690 - X", listOf(resolveRow(row(0, 1, "20"), rooms669)))
        assertFalse(showRoomFilter(single))
        assertTrue(showRoomFilter(source669()))
    }

    // ---- room colors ----

    @Test
    fun `room colors are distinct stable and unassigned is gray`() {
        val keys = source669().roomKeys + UNASSIGNED_ROOM_KEY
        val a = assignRoomColors(keys, "669 - WIECHERT 3146 NW CROSSINGS")
        val b = assignRoomColors(keys.reversed(), "669 - WIECHERT 3146 NW CROSSINGS")
        assertEquals(a, b)
        val named = keys.filter { it != UNASSIGNED_ROOM_KEY }.map { a.getValue(it) }
        assertEquals(named.size, named.toSet().size)
        assertTrue(named.all { it in ROOM_PALETTE })
        assertEquals(UNASSIGNED_ROOM_COLOR, a.getValue(UNASSIGNED_ROOM_KEY))
    }

    // ---- model ----

    @Test
    fun `room tags splits rows per room and keeps CV order`() {
        val s = source669()
        val m = buildCutListModel(s, defaultSelection(s).copy(roomTags = true), "1 October, 2026")
        val oak = m.materials.first { it.material == "3/4 2s White Oak Rift" }.rows
        assertEquals(
            listOf(
                Triple(1, "20", "Room #1 (KITCHEN)"),
                Triple(1, "20", "Room #1 (KITCHEN)"),
                Triple(1, "42", "Room #4 (UTILITY - BENCH)"),
                Triple(1, "23", "Room #2 (PANTRY)"),
                Triple(1, "30", "Room #3 (VANITIES)"),
            ),
            oak.map { Triple(it.qty, it.cabinetText, it.roomKey) }
        )
        assertEquals(listOf("3/4 2s White Oak Rift", "3/4 DOUBLE FUMED"), m.materials.map { it.material })
        assertEquals("Sheet", m.materials.first().unitsLabel)
    }

    @Test
    fun `standard mode keeps rows whole when all rooms checked`() {
        val s = source669()
        val m = buildCutListModel(s, defaultSelection(s), "d")
        val oak = m.materials.first().rows
        assertEquals(listOf(1, 2, 2), oak.map { it.qty })
        assertEquals(listOf("20", "20, 42", "23, 30"), oak.map { it.cabinetText })
        assertTrue(oak.all { it.roomKey == null })
        assertTrue(m.rooms.isEmpty())
        assertTrue(m.filteredRoomNames.isEmpty())
    }

    @Test
    fun `standard mode trims excluded rooms share and omits fully excluded rows`() {
        val s = source669()
        val sel = defaultSelection(s).copy(rooms = setOf("Room #1 (KITCHEN)", "Room #2 (PANTRY)"))
        val m = buildCutListModel(s, sel, "d")
        assertEquals(listOf("3/4 2s White Oak Rift"), m.materials.map { it.material }) // CLOSET-only material gone
        val oak = m.materials.single().rows
        assertEquals(listOf(Pair(1, "20"), Pair(1, "20"), Pair(1, "23")), oak.map { it.qty to it.cabinetText })
        assertEquals(listOf("KITCHEN", "PANTRY"), m.filteredRoomNames)
    }

    @Test
    fun `room tags legend uses colors from all source rooms`() {
        val s = source669()
        val all = buildCutListModel(s, defaultSelection(s).copy(roomTags = true), "d")
        val filtered = buildCutListModel(
            s, defaultSelection(s).copy(roomTags = true, rooms = setOf("Room #3 (VANITIES)")), "d"
        )
        val vanAll = all.rooms.first { it.key == "Room #3 (VANITIES)" }.color
        val vanFiltered = filtered.rooms.single().color
        assertEquals(vanAll, vanFiltered)
        assertEquals("VANITIES", filtered.rooms.single().displayName)
    }

    @Test
    fun `canPrint false when no material or no room selected`() {
        val s = source669()
        assertTrue(buildCutListModel(s, defaultSelection(s), "d").canPrint)
        assertFalse(buildCutListModel(s, defaultSelection(s).copy(materials = emptySet()), "d").canPrint)
        assertFalse(buildCutListModel(s, defaultSelection(s).copy(rooms = emptySet()), "d").canPrint)
        assertFalse(
            buildCutListModel(
                s, defaultSelection(s).copy(materials = setOf("3/4 DOUBLE FUMED"), rooms = setOf("Room #1 (KITCHEN)")), "d"
            ).canPrint
        )
    }

    @Test
    fun `option counts always equal printed pieces`() {
        val base = source669()
        val s = DoorPanelCutListSource(base.jobFolderName, base.rows + resolveRow(row(5, 4, "20, 42"), rooms669))
        val allRooms = s.roomKeys.toSet()
        val roomSelections = listOf(
            allRooms,
            allRooms - "Room #4 (UTILITY - BENCH)",
            setOf("Room #1 (KITCHEN)"),
            setOf(UNASSIGNED_ROOM_KEY),
        )
        for (roomTags in listOf(false, true)) {
            for (rooms in roomSelections) {
                val sel = CutListSelection(roomTags, s.materials.toSet(), rooms)
                val model = buildCutListModel(s, sel, "d")
                assertEquals(
                    "materials roomTags=$roomTags rooms=$rooms",
                    materialOptions(s, sel).filter { it.checked }.sumOf { it.pieces },
                    model.totalPieces
                )
                if (roomTags) {
                    roomOptions(s, sel).filter { it.checked }.forEach { opt ->
                        val printed = model.materials.sumOf { m -> m.rows.filter { it.roomKey == opt.key }.sumOf { it.qty } }
                        assertEquals("room ${opt.key} rooms=$rooms", opt.pieces, printed)
                    }
                }
            }
        }
    }

    @Test
    fun `total pieces and job number`() {
        val s = source669()
        assertEquals(1 + 2 + 2 + 2, buildCutListModel(s, defaultSelection(s), "d").totalPieces)
        assertEquals("669", cutListJobNumber("669 - WIECHERT 3146 NW CROSSINGS"))
        assertEquals("644d", cutListJobNumber("644d - SHOWROOM"))
    }

    // ---- buildDoorPanelCutListSource (JSON) ----

    private fun rowJson(
        id: String, page: Int, ordinal: Int, qty: Int, cab: String, material: String, unit: String,
        width: String = "10.0",
    ) = """{"rowId":"$id","page":$page,"rowOrdinal":$ordinal,"qty":$qty,"description":"Door Flat Panel 1A",""" +
        """"width":"$width","length":"20.0","cabinets":[],"rawCabinetText":"$cab","material":"$material","unitType":"$unit"}"""

    private fun cutlistJson(vararg rows: String) =
        """{"documents":[{"docType":"DOOR_CUT_LIST","pdfFilename":"x.pdf","pageCount":2,"rows":[${rows.joinToString(",")}]}]}"""

    @Test
    fun `source keeps sheet rows only in page then ordinal order`() {
        val json = cutlistJson(
            rowJson("DOOR_CUT_LIST:2:0", 2, 0, 1, "20", "1/4 MDF", "SHEETS", width = "8.0"),
            rowJson("DOOR_CUT_LIST:1:1", 1, 1, 1, "20", "1/4 MDF", "SHEETS", width = "9.0"),
            rowJson("DOOR_CUT_LIST:1:0", 1, 0, 1, "20", "1/4 MDF", "SHEETS", width = "9.5"),
            rowJson("DOOR_CUT_LIST:1:2", 1, 2, 2, "20 (2)", "3/4 Paint Grade Wood", "BD_FT"),
        )
        val source = buildDoorPanelCutListSource("684 - X", json, null)!!
        assertEquals(listOf("9.5", "9.0", "8.0"), source.rows.map { it.width })
        assertEquals(listOf("1/4 MDF"), source.materials)
        assertEquals(listOf(UNASSIGNED_ROOM_KEY), source.roomKeys) // no cabinet index
    }

    @Test
    fun `source is null when json missing corrupt or without sheet rows`() {
        assertNull(buildDoorPanelCutListSource("x", null, null))
        assertNull(buildDoorPanelCutListSource("x", "   ", null))
        assertNull(buildDoorPanelCutListSource("x", "{not json", null))
        assertNull(buildDoorPanelCutListSource("x", """{"documents":[]}""", null))
        assertNull(
            buildDoorPanelCutListSource(
                "x", cutlistJson(rowJson("DOOR_CUT_LIST:1:0", 1, 0, 1, "1", "3/4 Oak", "BD_FT")), null
            )
        )
        // No unitType anywhere → same rule as SpecialtyDoorPanelsScreen: hidden.
        val noUnit = cutlistJson(rowJson("DOOR_CUT_LIST:1:0", 1, 0, 1, "1", "1/4 MDF", "SHEETS"))
            .replace(""","unitType":"SHEETS"""", "")
        assertNull(buildDoorPanelCutListSource("x", noUnit, null))
    }

    @Test
    fun `source resolves rooms from cabinet index`() {
        val json = cutlistJson(rowJson("DOOR_CUT_LIST:1:0", 1, 0, 2, "63, 64", "3/4 DOUBLE FUMED", "SHEETS"))
        val index = CabinetSheetIndex(
            documents = CabinetSheetIndexDocuments(
                assembly = ReferenceDocumentIndex(
                    virtualCombined = AssemblyVirtualCombinedIndex(pageDetails = pages(page("Room #5 (CLOSET)", "63", "64")))
                )
            )
        )
        val source = buildDoorPanelCutListSource("669 - X", json, index)!!
        assertEquals(listOf("Room #5 (CLOSET)"), source.roomKeys)
        assertEquals(2, source.rows.single().groups.single().qty)
    }
}
