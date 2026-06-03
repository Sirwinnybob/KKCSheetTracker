package com.kkc.sheettracker.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTabRoutingTest {
    @Test
    fun homeTopLevelTabForWorkMode_matchesExpectedModes() {
        assertEquals(TopLevelTab.DASHBOARD, homeTopLevelTabForWorkMode(WorkMode.CNC))
        assertEquals(TopLevelTab.DASHBOARD, homeTopLevelTabForWorkMode(WorkMode.HARDWOODS))
        assertEquals(TopLevelTab.JOBS, homeTopLevelTabForWorkMode(WorkMode.ASSEMBLY))
        assertEquals(TopLevelTab.JOBS, homeTopLevelTabForWorkMode(WorkMode.SPECIALTY))
    }
}
