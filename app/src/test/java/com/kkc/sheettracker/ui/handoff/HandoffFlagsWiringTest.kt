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
        assertTrue(src.contains("editable = !readOnly && tabletId.isNotBlank()"))
        assertTrue(src.contains("handoff = handoffFlags.uiFor(handoffFlagRef(resolved.item))"))
        assertTrue(src.contains("handoff = handoffFlags.uiFor(handoffFlagRef(rip))"))
        assertTrue(src.contains("handoffFor = { item -> handoffFlags.uiFor(handoffFlagRef(item)) }"))
        assertTrue(src.contains("handoffFlags.message"))
        assertTrue(src.contains("consumeMessage()"))
    }

    @Test
    fun refusedTapMessageIsShownBeforeItIsConsumed() {
        // Consuming first changes the LaunchedEffect key and cancels a snackbar suspended in it.
        listOf(
            "ui/specialty/SpecialtyJobDetailScreen.kt" to "showSnackbar(text)",
            "ui/hardwoods/HardwoodsWorkspaceScreen.kt" to "showSnackbar(text)"
        ).forEach { (path, showCall) ->
            val src = source(path)
            val effect = src.indexOf("LaunchedEffect(handoffFlags.message)")
            assertTrue(path, effect >= 0)
            val show = src.indexOf(showCall, effect)
            val consume = src.indexOf("consumeMessage()", effect)
            assertTrue("$path show missing", show >= 0)
            assertTrue("$path consume missing", consume >= 0)
            assertTrue("$path must show before consuming", show < consume)
        }
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
    fun chipLifecycleRunsTapsInAppScopeAndReadsSignalInsideProducers() {
        val src = source("ui/handoff/HandoffTruckChip.kt")
        // Taps outlive the screen: no rememberCoroutineScope in the state holder.
        assertFalse(src.contains("rememberCoroutineScope"))
        assertTrue(src.contains("handoffRestScope.launch"))
        // The 30 s tick and the change signal are collected in producers, not read in composition.
        assertFalse(src.contains("collectAsState"))
        assertFalse(src.contains("tick++"))
        assertTrue(src.contains("HandoffFlagsChangeSignal.version.collect {"))
        assertTrue(src.contains("delay(TRUCK_LIST_MIN_INTERVAL_MS)"))
    }

    @Test
    fun jobFolderGuardsReportEverySkip() {
        listOf(
            "data/SheetRipProgressStore.kt", "data/TabletSpecialtyItemsStore.kt", "data/SpecialtyProgressStore.kt",
            "data/HardwoodsProgressStore.kt", "data/PdfMarkupStore.kt", "data/ProgressStore.kt",
            "data/HiddenMaterialsRequestStore.kt"
        ).forEach { path ->
            assertTrue("$path must call JobFolderGuardEvents.report", source(path).contains("JobFolderGuardEvents.report("))
        }
        // The flag tap shows its own message for a missing job, so the handoff store must not also report.
        assertFalse(source("data/HandoffFlagRequestStore.kt").contains("JobFolderGuardEvents.report("))
        assertTrue(source("MainActivity.kt").contains("JobFolderGuardEvents.skipped"))
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
