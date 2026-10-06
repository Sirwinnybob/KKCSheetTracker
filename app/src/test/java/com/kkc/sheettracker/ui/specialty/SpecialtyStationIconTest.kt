package com.kkc.sheettracker.ui.specialty

import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.ui.components.icons.HardwoodsBoardStockIcon
import com.kkc.sheettracker.ui.components.icons.HardwoodsSpecialtyIcon
import com.kkc.sheettracker.ui.components.icons.ReferenceAssemblyIcon
import com.kkc.sheettracker.ui.components.icons.ReferenceDeliveryIcon
import com.kkc.sheettracker.ui.components.icons.StationCncIcon
import com.kkc.sheettracker.ui.components.icons.StationEdgeBanderIcon
import com.kkc.sheettracker.ui.components.icons.StationSawIcon
import org.junit.Assert.assertEquals
import org.junit.Test

class SpecialtyStationIconTest {

    @Test
    fun `stations map to their icons`() {
        assertEquals(StationCncIcon, SpecialtyStation.CNC.icon())
        assertEquals(HardwoodsBoardStockIcon, SpecialtyStation.HARDWOODS.icon())
        assertEquals(StationSawIcon, SpecialtyStation.SAW.icon())
        assertEquals(StationEdgeBanderIcon, SpecialtyStation.EDGE_BANDER.icon())
        assertEquals(ReferenceAssemblyIcon, SpecialtyStation.ASSEMBLY.icon())
        assertEquals(HardwoodsSpecialtyIcon, SpecialtyStation.SPECIALTY.icon())
        assertEquals(ReferenceDeliveryIcon, SpecialtyStation.DELIVERY.icon())
    }

    @Test
    fun `section ids resolve to station icons and other has none`() {
        assertEquals(StationCncIcon, specialtySectionIcon(SpecialtyStation.CNC.name))
        assertEquals(null, specialtySectionIcon("other"))
    }
}
