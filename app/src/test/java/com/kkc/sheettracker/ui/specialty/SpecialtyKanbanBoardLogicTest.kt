package com.kkc.sheettracker.ui.specialty

import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_OTHER
import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS
import com.kkc.sheettracker.data.SpecialtyProgressStore
import com.kkc.sheettracker.data.models.SpecialtyCompletionState
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.ui.components.KKCPillAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpecialtyKanbanBoardLogicTest {

    private fun resolved(
        id: String,
        stations: List<SpecialtyStation>,
        done: Map<String, Boolean> = emptyMap(),
        category: SpecialtyItemCategory = SpecialtyItemCategory.CUSTOM,
        material: String? = null,
        orderDate: String? = null
    ) = SpecialtyResolvedItem(
        item = SpecialtyItem(
            id = id, name = id, category = category, stations = stations,
            material = material, orderDate = orderDate
        ),
        completionByKey = done.mapValues { SpecialtyCompletionState(completed = it.value) }
    )

    @Test
    fun columns_sheetRipsFirst_stationsInOrder_otherLast() {
        val saw = resolved("a", listOf(SpecialtyStation.SAW))
        val asm = resolved("b", listOf(SpecialtyStation.ASSEMBLY))
        val none = resolved("c", emptyList())
        val order = listOf(SpecialtyStation.ASSEMBLY, SpecialtyStation.SAW)
        val sections = buildSpecialtyDetailSections(listOf(saw, asm, none), order)

        val columns = buildSpecialtyKanbanColumns(sections, hasSheetRips = true)

        assertEquals(
            listOf(SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS, "ASSEMBLY", "SAW", SPECIALTY_VIEWER_SECTION_ID_OTHER),
            columns.map { it.id }
        )
        assertEquals(listOf("Sheet Rips", "Assembly", "Saw", "Other"), columns.map { it.label })
    }

    @Test
    fun columns_skipSheetRipsWhenNone_andEmptyStations() {
        val saw = resolved("a", listOf(SpecialtyStation.SAW))
        val sections = buildSpecialtyDetailSections(listOf(saw), SpecialtyStation.entries.toList())
        val columns = buildSpecialtyKanbanColumns(sections, hasSheetRips = false)
        assertEquals(listOf("SAW"), columns.map { it.id })
    }

    @Test
    fun columnToggle_picksStationKeyForSplitItems() {
        val item = resolved(
            "split", listOf(SpecialtyStation.SAW, SpecialtyStation.ASSEMBLY),
            done = mapOf("SAW" to true, "ASSEMBLY" to false)
        )
        val toggles = checklistTogglesForItem(item, emptyMap())
        assertEquals("SAW", kanbanColumnToggle(toggles, "SAW")!!.completionKey)
        assertEquals("ASSEMBLY", kanbanColumnToggle(toggles, "ASSEMBLY")!!.completionKey)
    }

    @Test
    fun columnToggle_usesSingleItemKeyForUnsplitItems() {
        val item = resolved(
            "order", listOf(SpecialtyStation.CNC, SpecialtyStation.SAW),
            category = SpecialtyItemCategory.TO_ORDER
        )
        val toggles = checklistTogglesForItem(item, emptyMap())
        assertEquals(SpecialtyProgressStore.ITEM_COMPLETION_KEY, kanbanColumnToggle(toggles, "SAW")!!.completionKey)
        assertNull(kanbanColumnToggle(emptyList(), "SAW"))
    }

    @Test
    fun stationDots_excludeCurrentColumn_inStationOrder_withDoneState() {
        val item = resolved(
            "split", listOf(SpecialtyStation.SAW, SpecialtyStation.CNC, SpecialtyStation.ASSEMBLY),
            done = mapOf("SAW" to true, "CNC" to true, "ASSEMBLY" to false)
        )
        val toggles = checklistTogglesForItem(item, emptyMap())
        val dots = kanbanStationDots(item, "ASSEMBLY", toggles, SpecialtyStation.entries.toList())
        assertEquals(
            listOf(SpecialtyStation.CNC to true, SpecialtyStation.SAW to true),
            dots.map { it.station to it.done }
        )
    }

    @Test
    fun cardOrder_doneDropsToBottom_bothGroupsKeepOrder() {
        val a = resolved("a", listOf(SpecialtyStation.SAW))
        val b = resolved("b", listOf(SpecialtyStation.SAW))
        val c = resolved("c", listOf(SpecialtyStation.SAW))
        val d = resolved("d", listOf(SpecialtyStation.SAW))
        val doneIds = setOf("a", "c")
        val ordered = orderKanbanCards(listOf(a, b, c, d)) { it.item.id in doneIds }
        assertEquals(listOf("b", "d", "a", "c"), ordered.map { it.item.id })
    }

    @Test
    fun detailLine_prefersMaterialThenOrderDate() {
        assertEquals("Material: Walnut", kanbanDetailLine(resolved("a", emptyList(), material = "Walnut", orderDate = "08-24").item))
        assertEquals("Order Date: 08-24", kanbanDetailLine(resolved("a", emptyList(), orderDate = "08-24").item))
        assertNull(kanbanDetailLine(resolved("a", emptyList(), material = "  ").item))
    }

    @Test
    fun actionRow_specialtyActionsFirst_dividerAfterLastSpecialty() {
        val specialty = listOf(KKCPillAction("Door Panels", {}), KKCPillAction("Split View", {}))
        val reference = listOf(KKCPillAction("Assembly", {}), KKCPillAction("Print", {}))
        val spec = specialtyActionRow(specialty, reference)
        assertEquals(listOf("Door Panels", "Split View", "Assembly", "Print"), spec.actions.map { it.label })
        assertEquals(1, spec.dividerAfterIndex)
        assertNull(specialtyActionRow(specialty, emptyList()).dividerAfterIndex)
    }

    @Test
    fun headerColor_keepsWhiteTextReadableForEveryStation() {
        val ids = com.kkc.sheettracker.data.models.SpecialtyStation.entries.map { it.name } +
            listOf(com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS, com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_OTHER)
        ids.forEach { id ->
            val bg = kanbanHeaderColor(kanbanColumnColor(id))
            val ratio = com.kkc.sheettracker.ui.theme.contrastRatio(androidx.compose.ui.graphics.Color.White, bg)
            org.junit.Assert.assertTrue("$id header contrast $ratio", ratio >= 4.5f)
        }
    }
}
