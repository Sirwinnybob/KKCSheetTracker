package com.kkc.sheettracker.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An endless basicMarquee redraws its screen every vsync while the tablet sits idle. On the
 * Jobs grid it kept CNC tablets at ~120 fps / ~110% CPU for up to an hour at a time (field
 * cpu_spikes logs, 8.6.0-8.6.1), and the full-screen Haze source made each frame costlier.
 */
class NoEndlessMarqueeTest {
    @Test
    fun noScreenUsesAnEndlessMarquee() {
        val offenders = mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                text.contains("basicMarquee(") && text.contains("Int.MAX_VALUE")
            }
            .map { it.name }
            .toList()
        assertTrue("Endless marquee found in: $offenders", offenders.isEmpty())
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
