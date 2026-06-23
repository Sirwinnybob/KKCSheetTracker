package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwoodsRowHelpersTest {
    @Test
    fun cutlistDimensionDisplay_omitsWidthSeparatorForLengthOnlyRows() {
        assertEquals(
            "36",
            cutlistDimensionDisplay(HardwoodCutlistRow(width = "", length = "36"))
        )
        assertEquals(
            "2 x 36",
            cutlistDimensionDisplay(HardwoodCutlistRow(width = "2", length = "36"))
        )
    }
}
