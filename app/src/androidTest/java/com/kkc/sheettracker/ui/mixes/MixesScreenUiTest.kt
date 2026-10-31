package com.kkc.sheettracker.ui.mixes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.cnc.CncMachines
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.models.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.kkc.sheettracker.data.mixservice.PgmEditHistoryView

/** Uses the actual renderer and presentation reducer, with no CNC calls or production fixtures. */
class MixesScreenUiTest {
    @Test fun catalogCollectorKeepsDestinationWhenMaterialMembershipChanges() {
        fun catalog(revision: String) = CatalogState.Live(MixFilesCatalog("J", "A", revision, emptyList()))
        val a = MutableStateFlow<CatalogState>(catalog("old"))
        val b = MutableStateFlow<CatalogState>(CatalogState.Loading)
        var sources by mutableStateOf(mapOf("A" to a))
        compose.setContent {
            val states = rememberMixCatalogStates("J", sources)
            Text("${states["A"].catalogSnapshot()?.folderRevision}:${states.keys.sorted().joinToString()}")
        }
        compose.onNodeWithText("old:A").assertExists()
        compose.runOnIdle { sources = mapOf("A" to a, "B" to b) }
        compose.waitForIdle()
        compose.runOnIdle { a.value = catalog("fresh") }
        compose.onNodeWithText("fresh:A, B").assertExists()
        compose.runOnIdle { sources = mapOf("A" to a) }
        compose.waitForIdle()
        compose.runOnIdle { a.value = catalog("newest") }
        compose.onNodeWithText("newest:A").assertExists()
    }

    @Test fun healthyCatalogStillOffersHistoryRetryAndPreservesWantedEdits() {
        val session = MixesSession()
        val material = Material("m.pdf", "Maple", 1, metadata = MaterialMetadata(pages = listOf(PageMetadata(pageNumber = 1, sheetFiles = listOf("a")))))
        val entry = MixFileEntry("Z:\\a.pgm", "a.pgm", linkedMapOf(), null, 1, null, true, false)
        val catalog = MixFilesCatalog("J", "Maple", "r", listOf(MixFileRow("Maple.mix", MixFileKind.MIX, null, null, "r", null, false, null, null, listOf(entry))))
        runBlocking {
            session.onCatalog(catalog)
            session.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView() }
            session.updateSecond(listOf("a.pgm"), SecondPassField.SECOND, true, emptyList())
            session.reloadHistory(listOf("a.pgm")) { null }
        }
        compose.setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            val presentation = buildMixesUi(MixesUiInput(listOf(MaterialMixesInput(CncMachines.scm, material, CatalogState.Live(catalog))), "Maple", secondPassDraft = session.secondPass))
            val ui = presentation.copy(secondPassApplyEnabled = presentation.secondPassApplyEnabled && session.secondHistoryConfirmed)
            MixesWorkspace("History fixture", "Maple", 1, ui, session.secondPass, false, listOf("a.pgm"), null,
                onBack = {}, onMaterial = {}, onTab = {}, onNew = {}, onDiscard = {}, onApply = {},
                onRefresh = {}, onLoadTheirs = {}, onReapply = {}, onDragStart = {}, onDrop = {}, onAdd = {},
                onSecondPass = { _, _, _ -> }, onSecondDiscard = {}, onSecondApply = {}, secondMessage = session.secondMessage,
                historyRetryVisible = !session.secondHistoryConfirmed,
                onHistoryRetry = { scope.launch { session.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView() } } })
        } }
        compose.onNodeWithText("Apply 2nd pass (1)").assertIsNotEnabled()
        compose.onNodeWithText("Retry second-pass history").performClick()
        compose.onNodeWithText("Apply 2nd pass (1)").assertIsEnabled()
        compose.onNodeWithText("Retry second-pass history").assertDoesNotExist()
    }
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
        var mixBusy by mutableStateOf(false)
        var secondBusy by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            val ui = buildMixesUi(MixesUiInput(listOf(MaterialMixesInput(CncMachines.scm, material, CatalogState.Live(catalog),
                statusByPage = mapOf(1 to SheetStatus.COMPLETE))), "Maple", draft = draft, secondPassDraft = second, conflict = conflict,
                applying = mixBusy, secondPassApplying = secondBusy))
            MixesWorkspace("Fixture · Mixes", "Maple", 3, ui, second, false, second.wanted.keys.toList(), null,
                onBack = {}, onMaterial = {}, onTab = {}, onNew = {}, onDiscard = {}, onApply = { applied++ },
                onRefresh = {}, onLoadTheirs = {}, onReapply = {}, onDragStart = {}, onDrop = {},
                onAdd = { page -> added++; draft = page.pgmFiles.fold(draft) { d, pgm -> MixDrafts.add(d, pgm) } },
                onSecondPass = { _, _, _ -> }, onSecondDiscard = {}, onSecondApply = {}, secondMessage = null,
                mixEditingEnabled = !mixBusy, secondEditingEnabled = !secondBusy, secondDiscardEnabled = !secondBusy)
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
        compose.runOnIdle { conflict = false; mixBusy = true }
        compose.onNodeWithText("Discard").assertIsNotEnabled()
        compose.onNodeWithTag("mixes-sheet-list").performScrollToNode(hasContentDescription("2ND · b.pgm"))
        compose.onNodeWithContentDescription("2ND · b.pgm").assertIsEnabled()
        compose.onNodeWithTag("mixes-sheet-list").performScrollToNode(hasText("Add"))
        compose.onAllNodesWithText("Add").onFirst().assertIsNotEnabled()
        compose.runOnIdle { mixBusy = false; secondBusy = true }
        compose.onAllNodesWithText("Add").onFirst().assertIsEnabled()
        compose.onNodeWithText("Discard 2nd pass").assertIsNotEnabled()
        compose.onNodeWithTag("mixes-sheet-list").performScrollToNode(hasContentDescription("2ND · b.pgm"))
        compose.onNodeWithContentDescription("2ND · b.pgm").assertIsNotEnabled()
    }
}
