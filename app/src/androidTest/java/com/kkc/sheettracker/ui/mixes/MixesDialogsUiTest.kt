package com.kkc.sheettracker.ui.mixes

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixservice.*
import com.kkc.sheettracker.ui.theme.KKCTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual Task 8 renderers with local callbacks only. No service/device-data mutation. */
class MixesDialogsUiTest {
    @get:Rule val compose = createComposeRule()
    private fun entry(pgm: String = "R870108N.pgm") = MixFileEntry("Z:\\Opaque Folder\\$pgm", pgm,
        linkedMapOf("R" to "2", "Unknown_Param" to " untouched = ä "), "opaque fields", 2, 1, true, false)
    private fun file(name: String = "MapleMix.mix", kind: MixFileKind = MixFileKind.MIX) =
        MixFileRow(name, kind, null, null, "current", null, false, null, null, listOf(entry()))
    private fun group() = MixGroup("maplemix.mix", "MapleMix", file(),
        file("MapleMix.frz", MixFileKind.FRZ),
        listOf(file("OLD2_MapleMix.mix", MixFileKind.REVISION).copy(revisionOf = "MapleMix.mix", revisionNumber = 2),
            file("OLD1_MapleMix.mix", MixFileKind.REVISION).copy(revisionOf = "MapleMix.mix", revisionNumber = 1)),
        listOf(file("OLD1_MapleMix.frz", MixFileKind.REVISION).copy(revisionOf = "MapleMix.frz", revisionNumber = 1)))
    private fun screenshot(name: String) {
        compose.waitForIdle()
        var rendered: android.graphics.Bitmap? = null
        // Semantics can settle a frame before SurfaceFlinger on the emulator. Wait for actual pixels,
        // including short panels whose content sits near the top rather than in the middle third.
        compose.waitUntil(5_000) {
            val candidate = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val tones = mutableSetOf<Int>()
            for (x in 64 until candidate.width - 64 step 16)
                for (y in 64 until candidate.height - 64 step 16) tones += candidate.getPixel(x, y)
            rendered = candidate
            tones.size > 16
        }
        val screenshot = requireNotNull(rendered)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "task8-$name-${context.resources.configuration.orientation}.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun mismatchRequiresChoiceForEveryPgmAndReturnsExactResolutions() {
        var resolutions: Map<String, String>? = null
        compose.setContent { KKCTheme {
            FrzMismatchDialog(listOf(FrzMismatch("A.pgm", 0, true, 1), FrzMismatch("Z.pgm", 1, false, 0)),
                { if (it == "A.pgm") "Complete" else "Not started" }, { resolutions = it }, {})
        } }
        compose.onNodeWithText("Apply choices").assertIsNotEnabled()
        compose.onAllNodesWithText("Tablet").onFirst().performClick()
        compose.onNodeWithText("Apply choices").assertIsNotEnabled()
        compose.onAllNodesWithText("FRZ", substring = false).onLast().performClick()
        screenshot("mismatch")
        compose.onNodeWithText("Apply choices").assertIsEnabled().performClick()
        assertEquals(mapOf("A.pgm" to "tablet", "Z.pgm" to "frz"), resolutions)
    }

    @Test fun interruptedEditorValidatesLeftAndPreservesOpaqueFields() {
        var saved: DraftEntry? = null
        val source = entry()
        val draft = DraftEntry(1, source, null, 2, 1, LinkedHashMap(source.parameters))
        compose.setContent { KKCTheme { EntryEditSheet(draft, true, { saved = it }, {}) } }
        compose.onNodeWithText("Quantity (R)").performTextReplacement("3")
        compose.onNodeWithText("Left", substring = false).performTextReplacement("4")
        compose.onNodeWithText("Save to draft").assertIsNotEnabled()
        compose.onNodeWithText("Left", substring = false).performTextReplacement("2")
        compose.onNodeWithText("Unknown_Param").assertDoesNotExist()
        screenshot("entry-frz")
        compose.onNodeWithText("Save to draft").performClick()
        val edited = requireNotNull(saved)
        assertEquals(3, edited.quantity); assertEquals(2, edited.remaining)
        assertEquals(source.path, edited.source!!.path)
        assertEquals(source.parameters, edited.parameters)
        assertEquals("opaque fields", edited.source.fields)
    }

    @Test fun plainEditorShowsEveryParameterAndNewPgmCannotInventSource() {
        val source = entry()
        var saved: DraftEntry? = null
        var showNew by mutableStateOf(false)
        compose.setContent { KKCTheme {
            val draft = if (showNew) DraftEntry(2, null, "new.pgm", 1, null, null)
                else DraftEntry(1, source, null, 2, null, LinkedHashMap(source.parameters))
            EntryEditSheet(draft, false, { saved = it; showNew = true }, {})
        } }
        compose.onNodeWithText("Unknown_Param").assertExists().performTextReplacement("new opaque = value")
        compose.onNodeWithText("Quantity (R)").performTextReplacement("3")
        screenshot("entry-mix")
        compose.onNodeWithText("Save to draft").performClick()
        assertEquals(linkedMapOf("R" to "3", "Unknown_Param" to "new opaque = value"), saved!!.parameters)
        compose.onNodeWithText("Apply the new sheet before editing its quantity or parameters.").assertExists()
        compose.onNodeWithText("Save to draft").assertIsNotEnabled()
    }
    @Test fun conflictBlocksEditorSaveButAllowsClosingForRecovery() {
        val source = entry()
        var closed by mutableStateOf(false)
        var busy by mutableStateOf(true)
        compose.setContent { KKCTheme {
            if (closed) Text("Ready to resolve conflict")
            else EntryEditSheet(DraftEntry(1, source, null, 2, null, LinkedHashMap(source.parameters)), false,
                { fail("A conflicted edit cannot save") }, { closed = true }, enabled = false, dismissEnabled = !busy,
                blockedReason = "This mix changed on the CNC. Close the editor to resolve the conflict.")
        } }
        compose.onNodeWithText("Save to draft").assertIsNotEnabled()
        compose.onNodeWithText("Cancel", substring = false).assertIsNotEnabled()
        compose.runOnIdle { busy = false }
        compose.onNodeWithText("Cancel", substring = false).assertIsEnabled().performClick()
        compose.onNodeWithText("Ready to resolve conflict").assertExists()
    }

    @Test fun revisionsViewBothFilesRestoreToCallbackAndConfirmPermanentDelete() {
        val group = group()
        var restored: MixFileRow? = null
        var deleted: MixFileRow? = null
        var pending by mutableStateOf<MixFileRow?>(null)
        compose.setContent { KKCTheme {
            RevisionsPanel(group, {}, { restored = it }, { pending = it }, {}, LocalConfiguration.current.screenWidthDp >= 1000)
            pending?.let { revision -> DeleteConfirm(revision.file, { deleted = revision; pending = null }, { pending = null }, permanent = true) }
        } }
        compose.onNodeWithText("made at CNC").assertExists()
        compose.onAllNodesWithText("View").onFirst().performClick()
        compose.onNodeWithText("Revision", substring = false).assertExists()
        screenshot("revisions")
        compose.onAllNodesWithText("Restore", substring = false).onFirst().performClick()
        assertEquals("OLD2_MapleMix.mix", restored!!.file)
        compose.onAllNodesWithText("Delete revision").onFirst().performClick()
        assertNull(deleted)
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertNull(deleted)
        compose.onAllNodesWithText("Delete revision").onFirst().performClick()
        compose.onNodeWithText("Delete permanently").performClick()
        assertEquals("OLD2_MapleMix.mix", deleted!!.file)
        compose.onNodeWithText(".FRZ", substring = false).performClick()
        compose.onNodeWithText("FRZ revisions are view-only.").assertExists()
        compose.onNodeWithText("Restore", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Delete revision").assertDoesNotExist()
    }

    @Test fun compareShowsBothEntryListsAndDeletedPlainMixRemainsRecoverable() {
        var compare by mutableStateOf(true)
        var restored: MixFileRow? = null
        val g = group()
        compose.setContent { KKCTheme {
            RevisionsPanel(if (compare) g else g.copy(mix = null, frz = null, frzRevisions = emptyList()), {},
                { restored = it }, {}, { compare = false }, LocalConfiguration.current.screenWidthDp >= 1000, compare = compare)
        } }
        compose.onNodeWithText(".mix", substring = false).assertExists()
        compose.onNodeWithText(".FRZ", substring = false).assertExists()
        compose.onAllNodesWithText("1. R870108N.pgm", substring = true).assertCountEquals(2)
        screenshot("compare")
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Deleted mix · no current file").assertExists()
        compose.onAllNodesWithText("Restore", substring = false).onFirst().performClick()
        assertEquals("MapleMix.mix", restored!!.revisionOf)
    }

    @Test fun splitNameDetectsFrzCollisionAndPermanentActionsWaitForConfirmation() {
        var file: String? = null
        var deleted = false
        var confirm by mutableStateOf(false)
        compose.setContent { KKCTheme {
            if (confirm) DeleteConfirm(file!!, { deleted = true }, { confirm = false })
            else SplitNameDialog("MapleMix 2", { file = it; confirm = true }, {}, taken = listOf("MAPLEMIX 2.FRZ"))
        } }
        compose.onNodeWithText("Move to draft").assertIsNotEnabled()
        compose.onNodeWithText("Mix name").performTextReplacement("MapleMix 3")
        compose.onNodeWithText("Move to draft").performClick()
        assertEquals("MapleMix 3.mix", file)
        assertFalse(deleted)
        compose.onNodeWithText("Delete", substring = false).performClick()
        assertTrue(deleted)
    }

    @Test fun historyUsesCanonicalResultAndRevertIsConfirmedAndFeatureGated() {
        val state = PgmEditCurrentState("super", true)
        val event = PgmEditHistoryEvent(eventId = "request:1", at = "2026-10-31T10:00:00", actor = "pgm-mix-service",
            action = "edit_succeeded", result = state, backup = "BACKUP_original/R1.pgm")
        var staged = false
        var supported by mutableStateOf(true)
        compose.setContent { KKCTheme {
            SecondPassHistorySheet("R1.pgm", listOf(event), supported, { staged = true; supported = false }, {}, current = state, revertSupported = supported)
        } }
        compose.onNodeWithText("unrecorded → super · P-unload removed").assertExists()
        screenshot("history")
        compose.onNodeWithText("Revert to original").performClick()
        assertFalse(staged)
        compose.onNodeWithText("Stage revert").performClick()
        assertTrue(staged)
        compose.onNodeWithText(REVERT_UNAVAILABLE).assertExists()
        compose.onNodeWithText("Revert to original").assertDoesNotExist()
    }
}
