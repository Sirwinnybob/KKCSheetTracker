package com.kkc.sheettracker.ui.supply

import com.kkc.sheettracker.data.models.SUPPLY_STATUS_PRIORITY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupplyModalChromeTest {
    @Test
    fun statusHeaderTintUsesTheSameStatusPaletteAsSupplyCards() {
        val urgentStatus = "LOW"
        val tier = SUPPLY_STATUS_PRIORITY[urgentStatus] ?: 99

        assertEquals(supplyStatusColor(tier), supplyStatusHeaderTint(urgentStatus))
    }

    @Test
    fun statusHeaderTintIsAbsentWhenStatusIsUnknown() {
        assertNull(supplyStatusHeaderTint(null))
        assertNull(supplyStatusHeaderTint(""))
    }
}
