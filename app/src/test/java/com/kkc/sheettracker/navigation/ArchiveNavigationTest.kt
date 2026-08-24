package com.kkc.sheettracker.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class ArchiveNavigationTest {

    @Test
    fun `standards hub no longer exposes Archive`() {
        assertFalse(source("ui/standards/StandardsHubScreen.kt").contains("ARCHIVE(\"Archive\""))
        assertFalse(source("navigation/NavGraph.kt").contains("standards/archive"))
    }

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
