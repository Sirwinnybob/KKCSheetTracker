package com.kkc.sheettracker.ui.mixes

import com.kkc.sheettracker.data.mixfiles.*
import org.junit.Assert.*
import org.junit.Test

class MixesLayoutTest {
    @Test fun initialMaterialSurvivesJobLoadingAndSelectsOnlyAfterMaterialsArrive() {
        assertEquals("Maple", selectAvailableMaterial("Maple", emptyList()))
        assertEquals("Maple", selectAvailableMaterial("Maple", listOf("Ply", "Maple")))
        assertEquals("Ply", selectAvailableMaterial("Deleted", listOf("Ply", "Maple")))
    }
    @Test fun useRailAtAndAbove1000dp() {
        assertTrue(useRail(1318)); assertFalse(useRail(824)); assertTrue(useRail(1000))
    }
    @Test fun stripColumnsFitTouchSizedTiles() {
        assertEquals(1, stripColumns(320)); assertEquals(3, stripColumns(824))
    }
    @Test fun draggingCollapsedAzRowMovesBothOccurrencesAndPreservesOtherDuplicates() {
        val d = draft("a.pgm", "z.pgm", "repeat.pgm", "repeat.pgm")
        val moved = moveRowBlock(d, listOf(1L, 2L), listOf(4L))
        assertEquals(listOf(3L, 4L, 1L, 2L), moved.entries.map { it.id })
        val back = moveRowBlock(moved, listOf(1L, 2L), listOf(3L))
        assertEquals(d.entries, back.entries)
    }
    @Test fun draggingLeavesCompletedOccurrenceInItsOriginalSlot() {
        val d = draft("a.pgm", "done.pgm", "z.pgm").copy(completedPgms = setOf("done.pgm"))
        assertEquals(listOf(3L, 2L, 1L), moveRowBlock(d, listOf(3L), listOf(1L)).entries.map { it.id })
        assertEquals(d, moveRowBlock(d, emptyList(), listOf(1L)))
    }
    private fun draft(vararg pgms: String): MixDraft = MixDraft("test.mix", "r", null, pgms.mapIndexed { i, pgm ->
        val source = MixFileEntry("Z:\\$pgm", pgm, linkedMapOf(), null, 1, null, true, false)
        DraftEntry(i + 1L, source, null, 1, null, linkedMapOf())
    }, null, emptyList(), 0)
}
