package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.ui.components.icons.HardwoodsPlankIcon
import com.kkc.sheettracker.ui.components.icons.HardwoodsSpecialtyIcon
import com.kkc.sheettracker.ui.components.icons.ReferenceAssemblyIcon
import com.kkc.sheettracker.ui.components.icons.StationCncIcon
import org.junit.Assert.assertEquals
import org.junit.Test

class ModeSwitcherIconTest {

    @Test
    fun `work modes use their station icons`() {
        assertEquals(StationCncIcon, WorkMode.CNC.modeIcon())
        assertEquals(HardwoodsPlankIcon, WorkMode.HARDWOODS.modeIcon())
        assertEquals(ReferenceAssemblyIcon, WorkMode.ASSEMBLY.modeIcon())
        assertEquals(HardwoodsSpecialtyIcon, WorkMode.SPECIALTY.modeIcon())
    }
}
