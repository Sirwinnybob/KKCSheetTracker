package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodDoorAssembly
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwoodsBatchUiTest {
    @Test
    fun batchLabelUsesTheBatchNameOrDefault() {
        assertEquals("Main", hardwoodsBatchLabel(HardwoodDocumentIndex(batchId = "b1", batchName = "Main")))
        assertEquals("Default", hardwoodsBatchLabel(HardwoodDocumentIndex(batchId = "b2", batchName = " ")))
    }

    @Test
    fun doorSummaryJoinsOnlyTheFieldsThatArePresent() {
        val full = HardwoodDoorAssembly(
            label = "D1", cabinet = "12", room = "Kitchen", width = "15", height = "30",
            style = "2 1/4\" Flat Panel", hinge = "Blum", grain = "Vertical", notes = "Paint grade"
        )
        assertEquals(
            "D1 · Cab 12 · Kitchen · 15 × 30 · 2 1/4\" Flat Panel · hinge Blum · grain Vertical · Paint grade",
            hardwoodsDoorSummaryLine(full)
        )
        assertEquals("Door · Cab 3", hardwoodsDoorSummaryLine(HardwoodDoorAssembly(cabinet = "3")))
        assertEquals("Door", hardwoodsDoorSummaryLine(HardwoodDoorAssembly(width = "15")))
    }
}
