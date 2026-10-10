package com.kkc.sheettracker.ui.handoff

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffFlagsWiringTest {
    @Test
    fun specialtyDetailShowsChipsOnRowsKanbanAndSheetRips() {
        val src = source("ui/specialty/SpecialtyJobDetailScreen.kt")
        assertTrue(src.contains("rememberHandoffFlags("))
        assertTrue(src.contains("editable = !readOnly"))
        assertTrue(src.contains("handoff = handoffFlags.uiFor(handoffFlagRef(resolved.item))"))
        assertTrue(src.contains("handoff = handoffFlags.uiFor(handoffFlagRef(rip))"))
        assertTrue(src.contains("handoffFor = { item -> handoffFlags.uiFor(handoffFlagRef(item)) }"))
        assertTrue(src.contains("handoffFlags.message"))
        assertTrue(src.contains("consumeMessage()"))
    }

    @Test
    fun kanbanCardRendersChip() {
        assertTrue(source("ui/specialty/SpecialtyKanbanBoard.kt").contains("HandoffTruckChip("))
    }

    @Test
    fun hardwoodsBoardStockShowsChipAndArchiveIsReadOnly() {
        val hw = source("ui/hardwoods/HardwoodsWorkspaceScreen.kt")
        assertTrue(hw.contains("rememberHandoffFlags("))
        assertTrue(hw.contains("handoffFlags.uiFor(handoffFlagRef(item))"))
        assertTrue(source("navigation/ArchiveJobDetailHost.kt").contains("handoffEditable = false"))
    }

    @Test
    fun nothingEverWritesHandoffJson() {
        listOf("data/HandoffFlags.kt", "data/HandoffFlagRequestStore.kt", "data/HandoffFlagsService.kt", "ui/handoff/HandoffTruckChip.kt").forEach { path ->
            val src = source(path)
            assertFalse(path, Regex("""atomicWriteFile\(\s*handoffJsonFile""").containsMatchIn(src))
            assertFalse(path, src.contains("handoffJsonFile(") && src.contains("writeText"))
        }
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
        error("Missing source $relativePath")
    }
}
