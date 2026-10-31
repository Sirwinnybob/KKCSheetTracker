package com.kkc.sheettracker.ui.mixes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.cnc.CncMachines
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.models.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Uses the actual renderer and presentation reducer, with no CNC calls or production fixtures. */
class MixesScreenUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun workspaceShowsMaterialsRowsAndIndependentApplyStates() {
        val material = Material("m.pdf", "Maple", 3, metadata = MaterialMetadata(pages = listOf(
            PageMetadata(pageNumber = 1, sheetFiles = listOf("a")),
            PageMetadata(pageNumber = 2, sheetFiles = listOf("b")),
            PageMetadata(pageNumber = 3, sheetFiles = listOf("c")))))
        fun entry(pgm: String) = MixFileEntry("Z:\\$pgm", pgm, linkedMapOf(), null, 1, null, true, false)
        val file = MixFileRow("Maple.mix", MixFileKind.MIX, null, null, "r", null, false, null, null, listOf(entry("a.pgm"), entry("b.pgm")))
        val catalog = MixFilesCatalog("fixture", "Maple", "folder", listOf(file))
        val group = groupMixFiles(catalog).single()
        var draft by mutableStateOf(MixDrafts.remove(MixDrafts.start(group, listOf("a.pgm")), 2))
        var applied = 0
        var added = 0
        val baseline = SecondPassDrafts.from(null, listOf("a.pgm", "b.pgm", "c.pgm"))
        val completedState = SecondPassState(true, false, false)
        val appliedHistory = baseline.copy(wanted = baseline.wanted + ("a.pgm" to completedState),
            current = baseline.current + ("a.pgm" to completedState))
        val second = SecondPassDrafts.set(appliedHistory, "b.pgm", SecondPassState(true, false, false))
        var conflict by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            val ui = buildMixesUi(MixesUiInput(listOf(MaterialMixesInput(CncMachines.scm, material, CatalogState.Live(catalog),
                statusByPage = mapOf(1 to SheetStatus.COMPLETE))), "Maple", draft = draft, secondPassDraft = second, conflict = conflict))
            MixesWorkspace("Fixture · Mixes", "Maple", 3, ui, second, false, second.wanted.keys.toList(), null,
                onBack = {}, onMaterial = {}, onTab = {}, onNew = {}, onDiscard = {}, onApply = { applied++ },
                onRefresh = {}, onLoadTheirs = {}, onReapply = {}, onDragStart = {}, onDrop = {},
                onAdd = { page -> added++; draft = page.pgmFiles.fold(draft) { d, pgm -> MixDrafts.add(d, pgm) } },
                onSecondPass = { _, _, _ -> }, onSecondDiscard = {}, onSecondApply = {}, secondMessage = null)
        } }
        compose.onNodeWithTag("mixes-apply").assertIsEnabled().performClick()
        assertEquals(1, applied)
        compose.onNodeWithText("Apply 2nd pass (1)").assertIsEnabled()
        compose.onNodeWithTag("mixes-sheet-list").performScrollToNode(hasText("COMPLETED · LOCKED"))
        compose.onNodeWithText("COMPLETED · LOCKED").assertExists()
        compose.onNodeWithContentDescription("2ND · a.pgm").assertIsOn().assertIsNotEnabled()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val shot = File(target.getExternalFilesDir(null), "task7-mixes-${target.resources.configuration.orientation}.png")
        shot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("mixes-sheet-list").performScrollToNode(hasText("Add"))
        compose.onAllNodesWithText("Add").onFirst().performClick()
        assertEquals(1, added)
        compose.runOnIdle { conflict = true }
        compose.onNodeWithTag("mixes-apply").assertIsNotEnabled()
        compose.onNodeWithText("Load theirs").assertExists()
        compose.onNodeWithText("Re-apply my changes").assertExists()
        compose.onNodeWithText("Apply 2nd pass (1)").assertIsEnabled()
    }
}
