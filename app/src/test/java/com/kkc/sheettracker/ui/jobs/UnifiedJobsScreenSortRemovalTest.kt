package com.kkc.sheettracker.ui.jobs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class UnifiedJobsScreenSortRemovalTest {
    @Test
    fun sortByNameStateAndSortButtonAreGone() {
        val source = unifiedJobsScreenSource()
        assertFalse("sortByName state must be removed", source.contains("sortByName"))
        assertFalse("Sort icon import must be removed", source.contains("automirrored.filled.Sort"))
        assertFalse("SortByAlpha icon import must be removed", source.contains("filled.SortByAlpha"))
    }

    private fun unifiedJobsScreenSource(): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate UnifiedJobsScreen.kt from ${System.getProperty("user.dir")}")
    }
}
