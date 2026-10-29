package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CncLoadedSheetRouteTest {
    @Test fun loadedRouteEncodesNamesAndExplicitFlagWithoutBreakingMixQueries() {
        assertEquals("viewer/709+-+BRAD+TAYLOR/709+-+19mm+Pre_Finished.pdf/11?preferStartPage=true",
            viewerRoute("709 - BRAD TAYLOR", "709 - 19mm Pre_Finished.pdf", 11, preferStartPage = true))
        assertEquals("viewer/j/m.pdf/11?mixName=MIX+A&mixPages=11,3,6&preferStartPage=true",
            viewerRoute("j", "m.pdf", 11, ViewerMixSelection("MIX A", listOf(11, 3, 6)), true))
    }
    private fun source(name: String): String = listOf(File("src/main/java/com/kkc/sheettracker/navigation/$name"),
        File("app/src/main/java/com/kkc/sheettracker/navigation/$name")).first { it.isFile }.readText()
    @Test fun bothGraphsWireExplicitUnscopedLoadedOpensAndParseTheFlag() {
        val nav = source("NavGraph.kt")
        val calls = Regex("UnifiedModeDashboardSpec\\.Cnc\\(").findAll(nav).map { match ->
            val start = match.range.last + 1
            var depth = 1; var end = start
            while (depth > 0) { when (nav[end++]) { '(' -> depth++; ')' -> depth-- } }
            nav.substring(start, end)
        }.toList()
        assertEquals(4, calls.size)
        calls.forEach { assertTrue("CNC construction missing loaded callback", it.contains("onOpenLoadedSheet =")) }
        assertEquals(2, Regex("navArgument\\(\"preferStartPage\"\\)").findAll(nav).count())
        assertEquals(2, Regex("preferStartPage = backStack.arguments\\?\\.getBoolean").findAll(nav).count())
        assertTrue(nav.contains("coordinator.openSheetInJobs(folderName, pdfFilename, page, preferStartPage = true)"))
        assertEquals(2, Regex("openSheetLegacy\\(folderName, pdfFilename, page, preferStartPage = true\\)").findAll(nav).count())
        val coordinator = source("NavigationCoordinator.kt")
        assertTrue(coordinator.contains("if (!preferStartPage && isCurrentViewerTarget"))
        assertTrue(coordinator.contains("launchSingleTop = !preferStartPage"))
    }
}
