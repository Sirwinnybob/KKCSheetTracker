package com.kkc.sheettracker.ui.managecode

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * An indeterminate progress bar animates every vsync. While the Mix Service compiled, the Manage
 * Code screen sat at 60 fps / ~85% CPU for over a minute with nobody touching it (field cpu_spikes
 * log 2026-10-06, SM-X808U-5254), each frame also re-blurring the Haze navbar. Compiling shows a
 * once-a-second elapsed timer instead.
 */
class ManageCodeNoEndlessProgressTest {
    @Test
    fun manageCodeScreenHasNoIndeterminateProgressIndicator() {
        val source = File(mainSourceRoot(), "ui/managecode/ManageCodeScreen.kt").readText()
        val indeterminate = Regex("""(Linear|Circular)ProgressIndicator\((?!\s*progress\s*=)""")
        assertFalse(
            "Indeterminate progress indicator in ManageCodeScreen.kt",
            indeterminate.containsMatchIn(source)
        )
    }

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker")
            if (candidate.isDirectory) return candidate
            val direct = File(dir, "src/main/java/com/kkc/sheettracker")
            if (direct.isDirectory) return direct
            dir = dir.parentFile ?: return@repeat
        }
        error("main source root not found")
    }
}
