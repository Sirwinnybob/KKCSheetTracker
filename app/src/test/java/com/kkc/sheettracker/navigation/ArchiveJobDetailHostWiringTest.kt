package com.kkc.sheettracker.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveJobDetailHostWiringTest {
    @Test
    fun archiveHostOwnsAndClosesOneSession() {
        val source = source("navigation/ArchiveJobDetailHost.kt")

        assertTrue(source.contains("remember(archiveJobId, folderName, contentVersion"))
        assertTrue(source.contains("DisposableEffect(session)"))
        assertTrue(source.contains("onDispose { session.close() }"))
        assertTrue(source.contains("JobDetailScreen("))
        assertTrue(source.contains("HardwoodsJobDetailScreen("))
        assertTrue(source.contains("AssemblyJobDetailScreen("))
        assertTrue(source.contains("SpecialtyJobDetailScreen("))
        assertTrue(source.contains("session.pdfMarkupStore"))
        assertTrue(source.contains("session.sheetRipProgressStore"))
        assertTrue(source.contains("session.baseDir.absolutePath"))
        assertFalse(source.contains("ClockInState"))
    }

    @Test
    fun bothArchiveRootRoutesDelegateToTheSharedHost() {
        val source = source("navigation/NavGraph.kt")

        assertEquals(2, Regex("ArchiveLibraryHost\\(").findAll(source).count())
        assertFalse(source.contains("Archive job detail view not yet available"))
    }

    @Test
    fun archiveIsPlacedAfterSafetyInTheLibraryAndNotBottomNavigation() {
        val standards = source("ui/standards/StandardsHubScreen.kt")
        val safetyIndex = standards.indexOf("SAFETY(")
        val archiveIndex = standards.indexOf("ARCHIVE(")
        assertTrue(safetyIndex >= 0)
        assertTrue(archiveIndex > safetyIndex)
        assertTrue(standards.contains("onOpenArchive: () -> Unit"))

        val navGraph = source("navigation/NavGraph.kt")
        assertEquals(2, Regex("onOpenArchive =").findAll(navGraph).count())
        assertFalse(navGraph.contains("NavDestination.ARCHIVE"))
        assertFalse(navGraph.contains("TopLevelTab.ARCHIVE"))
        assertFalse(navGraph.contains("route != \"archive\""))

        val coordinator = source("navigation/NavigationCoordinator.kt")
        assertFalse(coordinator.contains("ARCHIVE"))

        val appScaffold = source("ui/components/AppScaffold.kt")
        assertFalse(appScaffold.contains("NavDestination.ARCHIVE"))
        assertFalse(appScaffold.contains("icons.filled.Archive"))
        assertFalse(appScaffold.contains("icons.outlined.Archive"))
    }

    @Test
    fun archiveHostContainsAllNestedChildRoutes() {
        val source = source("navigation/ArchiveJobDetailHost.kt")

        assertTrue(source.contains("composable(\"detail\")"))
        assertTrue(source.contains("viewer/{pdfFilename}/{startPage}"))
        assertTrue(source.contains("referenceViewer/{docType}/{startPage}"))
        assertTrue(source.contains("hardwoods/workspace/{docType}/{rowId}"))
        assertTrue(source.contains("assembly/viewer/{folderName}/{startPageAssembly}/{startPagePlans}"))
    }

    private fun source(relativePath: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/$relativePath")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/$relativePath")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate source file $relativePath from ${System.getProperty("user.dir")}")
    }
}
