package com.kkc.sheettracker.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveViewerWiringTest {

    @Test
    fun eachViewerHasAnInjectedStoreAndReadOnlyFallback() {
        viewerSources().forEach { source ->
            assertTrue(source.contains("overridePdfMarkupStore: PdfMarkupStore? = null"))
            val fallback = source.indexOf("overridePdfMarkupStore ?: remember")
            val trackerPrefsLookup = source.indexOf("val trackerPrefs =")
            assertTrue("fallback must prefer the injected store", fallback >= 0)
            assertTrue("preference lookup must stay inside the fallback", trackerPrefsLookup > fallback)
            val fallbackSource = source.substring(fallback)
            assertTrue(fallbackSource.contains("getString(\"base_path\""))
            assertTrue(fallbackSource.contains("getString(\"tablet_id\""))
            assertTrue(fallbackSource.contains("readOnly = pdfMarkupReadOnly"))
        }
    }

    @Test
    fun eachLiveNavGraphPassesViewOnlyStateToAllFourViewerCallSites() {
        val source = navGraphSource()
        val legacyStart = source.indexOf("private fun LegacySingleStackNavigation(")
        assertTrue("legacy navigation function must exist", legacyStart >= 0)
        val multiSource = source.substring(0, legacyStart)
        val legacySource = source.substring(legacyStart)
        val viewerNames = listOf(
            "SheetViewerScreen(",
            "ReferencePdfViewerScreen(",
            "HardwoodsWorkspaceScreen(",
            "AssemblyViewerScreen("
        )
        assertEquals(4, Regex("pdfMarkupReadOnly = isViewOnlyMode").findAll(multiSource).count())
        assertEquals(4, Regex("pdfMarkupReadOnly = isViewOnlyMode").findAll(legacySource).count())
        viewerNames.forEach { viewerName ->
            assertEquals(1, multiSource.split(viewerName).size - 1)
            assertEquals(1, legacySource.split(viewerName).size - 1)
        }
    }

    private fun viewerSources(): List<String> = listOf(
        source("ui/viewer/SheetViewerScreen.kt"),
        source("ui/viewer/ReferencePdfViewerScreen.kt"),
        source("ui/assembly/AssemblyViewerScreen.kt"),
        source("ui/hardwoods/HardwoodsWorkspaceScreen.kt")
    )

    private fun navGraphSource(): String = source("navigation/NavGraph.kt")

    private fun source(relativePath: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/$relativePath")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/$relativePath")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("Could not locate $relativePath from working dir ${System.getProperty("user.dir")}")
    }
}
