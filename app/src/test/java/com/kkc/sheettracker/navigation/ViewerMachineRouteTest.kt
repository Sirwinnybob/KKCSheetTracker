package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerMachineRouteTest {
    @Test fun viewerRouteCarriesTheOwningMachine() {
        assertEquals("viewer/711/19mm.pdf/2?mixName=A&mixFile=A.mix&machine=scm-new",
            viewerRoute("711", "19mm.pdf", 2, ViewerMixSelection("A", "A.mix"), machineId = "scm-new"))
        assertEquals("viewer/711/19mm.pdf/1", viewerRoute("711", "19mm.pdf", 1))
        assertTrue(VIEWER_ROUTE_PATTERN.endsWith("&machine={machine}"))
    }

    @Test fun manageCodeRouteKeepsJobMaterialAndMachineTogether() {
        assertEquals("manage_code/711+Kitchen?material=19mm+Maple&machine=scm-new", manageCodeRoute("711 Kitchen", "19mm Maple", "scm-new"))
        assertEquals("manage_code/711+Kitchen?material=19mm+Maple", manageCodeRoute("711 Kitchen", "19mm Maple"))
        assertEquals("manage_code/711", manageCodeRoute("711"))
    }
}
