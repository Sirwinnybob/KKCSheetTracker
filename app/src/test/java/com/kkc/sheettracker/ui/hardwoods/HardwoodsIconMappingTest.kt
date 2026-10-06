package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.BoardStockSource
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.ui.components.icons.HardwoodsClosetRodIcon
import com.kkc.sheettracker.ui.components.icons.HardwoodsDoorPartsIcon
import com.kkc.sheettracker.ui.components.icons.HardwoodsFaceFrameIcon
import com.kkc.sheettracker.ui.components.icons.HardwoodsNailerIcon
import com.kkc.sheettracker.ui.components.icons.HardwoodsSpecialtyIcon
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwoodsIconMappingTest {

    @Test
    fun `each cut list type maps to its icon`() {
        assertEquals(HardwoodsFaceFrameIcon, HardwoodDocType.FACE_FRAME_CUT_LIST.icon())
        assertEquals(HardwoodsNailerIcon, HardwoodDocType.NAILER_CUT_LIST.icon())
        assertEquals(HardwoodsDoorPartsIcon, HardwoodDocType.DOOR_CUT_LIST.icon())
        assertEquals(HardwoodsClosetRodIcon, HardwoodDocType.CLOSET_ROD_CUT_LIST.icon())
        assertEquals(HardwoodsDoorPartsIcon, HardwoodDocType.DOOR_LIST.icon())
    }

    @Test
    fun `each rip list source matches its cut list icon`() {
        assertEquals(HardwoodDocType.FACE_FRAME_CUT_LIST.icon(), BoardStockSource.FRAME.icon())
        assertEquals(HardwoodDocType.NAILER_CUT_LIST.icon(), BoardStockSource.NAILER.icon())
        assertEquals(HardwoodDocType.DOOR_CUT_LIST.icon(), BoardStockSource.DOOR.icon())
        assertEquals(HardwoodsSpecialtyIcon, BoardStockSource.MANUAL.icon())
    }
}
