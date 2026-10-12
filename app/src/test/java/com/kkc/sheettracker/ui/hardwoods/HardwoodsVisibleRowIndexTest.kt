package com.kkc.sheettracker.ui.hardwoods

import org.junit.Assert.assertEquals
import org.junit.Test

class HardwoodsVisibleRowIndexTest {
    private val sections = listOf("paint" to listOf("p1", "p2"), "alder" to listOf("a1", "a2"))

    @Test
    fun materialExpansionLookupUsesTheDocumentAndMaterialIdentity() {
        val material = "3/4 Paint Grade Wood"
        val doorKey = hardwoodsPartSectionStateKey("DOOR_CUT_LIST", material)
        val frameKey = hardwoodsPartSectionStateKey("FACE_FRAME_CUT_LIST", material)
        val expansionRows = mapOf(doorKey to 74, frameKey to 12)
        assertEquals(74, expansionRows.getValue(hardwoodsPartSectionStateKey("DOOR_CUT_LIST", material)))
        assertEquals(12, expansionRows.getValue(hardwoodsPartSectionStateKey("FACE_FRAME_CUT_LIST", material)))
        assertEquals(mapOf("row" to 1), hardwoodsVisibleRowIndices(listOf(doorKey to listOf("row")), setOf(frameKey)))
    }

    @Test
    fun collapsedSectionsKeepOnlyTheirHeaderInTheLazyIndex() {
        assertEquals(mapOf("a1" to 2, "a2" to 3), hardwoodsVisibleRowIndices(sections, setOf("paint")))
        assertEquals(emptyMap<String, Int>(), hardwoodsVisibleRowIndices(sections, setOf("paint", "alder")))
    }

    @Test
    fun expandedRowsAndSectionHeadersBothCountTowardJumpIndices() {
        assertEquals(mapOf("p1" to 1, "p2" to 2, "a1" to 4, "a2" to 5), hardwoodsVisibleRowIndices(sections, emptySet()))
        assertEquals(mapOf("a2" to 1), hardwoodsVisibleRowIndices(listOf("alder" to listOf("a2")), emptySet()))
    }
}
