package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class ViewerRouteMixFileTest {
    @Test fun viewerRouteCarriesMixFileNotPages() {
        val selection = ViewerMixSelection("19MM PRE_FINISHEDMIX", "19MM PRE_FINISHEDMIX.FRZ")
        val route = viewerRoute("709 - Job", "19mm.pdf", 2, selection)
        assertFalse(route.contains("mixPages"))
        val args = route.substringAfter('?').split('&').associate { field -> field.substringBefore('=') to URLDecoder.decode(field.substringAfter('='), "UTF-8") }
        assertEquals(selection, viewerMixSelection(args["mixName"], args["mixFile"]))
    }
    @Test fun namesSurviveOneNavigationDecodeIncludingLiteralPercent20() {
        val name = "19 — # + %20 50%"
        val selection = ViewerMixSelection(name, "$name.FRZ")
        val route = viewerRoute(name, "$name.pdf", 1, selection)
        val path = route.substringBefore('?').split('/')
        assertEquals(name, URLDecoder.decode(path[1], "UTF-8"))
        assertEquals("$name.pdf", URLDecoder.decode(path[2], "UTF-8"))
        val args = route.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(selection, viewerMixSelection(args["mixName"], args["mixFile"]))
        assertFalse("Android Uri.decode preserves plus; spaces must be percent encoded", route.contains('+'))
    }
}
