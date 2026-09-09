package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwoodsWorkspaceRouteTest {
    @Test
    fun hardwoodsWorkspaceRoute_encodesModeAsTrailingSegment() {
        assertEquals(
            "hardwoods/workspace/1234+-+Kitchen/NAILER_CUT_LIST/row-1/HARDWOODS",
            hardwoodsWorkspaceRoute("1234 - Kitchen", HardwoodDocType.NAILER_CUT_LIST, "row-1", HiddenMaterialsMode.HARDWOODS)
        )
    }

    @Test
    fun hardwoodsWorkspaceRoute_specialtyModeEncodesDistinctly() {
        assertEquals(
            "hardwoods/workspace/1234+-+Kitchen/DOOR_CUT_LIST/_/SPECIALTY",
            hardwoodsWorkspaceRoute("1234 - Kitchen", HardwoodDocType.DOOR_CUT_LIST, null, HiddenMaterialsMode.SPECIALTY)
        )
    }
}
