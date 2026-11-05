package com.kkc.sheettracker.ui.cnc

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The CUTTING dot used to pulse forever (rememberInfiniteTransition). That redrew the dashboard and
 * CNC viewer at 55-100 fps while idle, 60-85% CPU for up to 25 min (field cpu_spikes logs, 9.0.8-9.0.13),
 * with the Haze navbar re-blurring each frame. The dot must stay static.
 */
class NoEndlessCncPulseTest {
    @Test
    fun statusPillHasNoInfiniteAnimation() {
        val source = pillSource().readText()
        assertFalse("CncStatusPill must not run an infinite animation",
            source.contains("rememberInfiniteTransition") || source.contains("infiniteRepeatable"))
    }

    private fun pillSource(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val rel = "src/main/java/com/kkc/sheettracker/ui/cnc/CncStatusPill.kt"
            File(dir, "app/$rel").takeIf { it.isFile }?.let { return it }
            File(dir, rel).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile ?: return@repeat
        }
        error("CncStatusPill.kt not found")
    }
}
