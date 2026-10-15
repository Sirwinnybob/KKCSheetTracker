package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwoodsBatchUiTest {
    @Test
    fun batchLabelUsesTheBatchNameOrDefault() {
        assertEquals("Main", hardwoodsBatchLabel(HardwoodDocumentIndex(batchId = "b1", batchName = "Main")))
        assertEquals("Default", hardwoodsBatchLabel(HardwoodDocumentIndex(batchId = "b2", batchName = " ")))
    }
}
