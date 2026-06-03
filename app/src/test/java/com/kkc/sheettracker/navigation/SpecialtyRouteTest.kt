package com.kkc.sheettracker.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class SpecialtyRouteTest {
    @Test
    fun specialtyJobRoute_encodesFolderName() {
        assertEquals(
            "specialty/job/1234+-+Kitchen+%26+Bath",
            specialtyJobRoute("1234 - Kitchen & Bath")
        )
    }

    @Test
    fun specialtyDoorPanelsRoute_encodesFolderName() {
        assertEquals(
            "specialty/door-panels/1234+-+Kitchen+%26+Bath",
            specialtyDoorPanelsRoute("1234 - Kitchen & Bath")
        )
    }

    @Test
    fun specialtySplitViewRoute_matchesStandardAssemblyRouteShape() {
        assertEquals(
            "assembly/viewer/1234+-+Kitchen+%26+Bath/3/7",
            specialtySplitViewRoute(
                jobFolderName = "1234 - Kitchen & Bath",
                assemblyPage = 3,
                plansPage = 7
            )
        )
    }

    @Test
    fun specialtySplitViewRoute_ignoresSpecialtyRoomQueryCompatibilityParam() {
        assertEquals(
            "assembly/viewer/1234+-+Kitchen+%26+Bath/3/7",
            specialtySplitViewRoute(
                jobFolderName = "1234 - Kitchen & Bath",
                assemblyPage = 3,
                plansPage = 7,
                room = "MAIN ROOM"
            )
        )
    }
}
