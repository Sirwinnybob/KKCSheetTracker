package com.kkc.sheettracker.ui.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.cnc.*
import com.kkc.sheettracker.data.models.*
import com.kkc.sheettracker.ui.cnc.*
import com.kkc.sheettracker.ui.components.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CncLiveViewerTest {
    @get:Rule val compose = createComposeRule()
    private val ref = CncSheetRef("709 - BRAD TAYLOR", "m.pdf", 11)
    @Test fun headerUpdatesAndLoadedTakesPriorityWithoutMutations() {
        val marks = mutableStateOf(CncSheetMarks(CncLoadedMark.Matched(ref, "R1", true), CncFinishedMark(ref, "7:04")))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalLowEndMode provides LowEndModeFlags(true, false, false, false, false)) {
                cncPillForSheet(marks.value, ref)?.let { CncStatusPill(it) }
            }
        } }
        compose.onNodeWithText("ON CNC · CUTTING").assertExists()
        compose.onNodeWithText("CUT ON CNC 7:04").assertDoesNotExist()
        compose.runOnIdle { marks.value = marks.value.copy(loaded = CncLoadedMark.Matched(ref, "R1", false)) }
        compose.onNodeWithText("LOADED · IDLE").assertExists()
        compose.runOnIdle { marks.value = marks.value.copy(loaded = null) }
        compose.onNodeWithText("CUT ON CNC 7:04").assertExists()
        compose.runOnIdle { marks.value = CncSheetMarks() }
        compose.onNodeWithText("CUT ON CNC 7:04").assertDoesNotExist()
    }
    @Test fun navigatorUsesPhysicalMixPageAndDropsRemovedMarksWhileOpen() {
        val material = Material("m.pdf", "m", 11, metadata = MaterialMetadata(pages =
            listOf(PageMetadata(pageNumber = 11, sheetId = "R1"), PageMetadata(pageNumber = 3), PageMetadata(pageNumber = 6))))
        val marks = mutableStateOf(CncSheetMarks(CncLoadedMark.Matched(ref, "R1", false)))
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "absent-cnc-test.pdf")
        val thumbnails = mutableMapOf<Int, android.graphics.Bitmap?>(11 to null, 3 to null, 6 to null)
        compose.setContent { MaterialTheme {
            SheetNavigatorSheet(listOf(11, 3, 6), 11, material.metadata!!.pages, emptyMap(),
                mutableMapOf(), thumbnails, file, "", onSelectPage = {}, onDismiss = {},
                cncPillsByPage = cncPillsForMaterial(marks.value, ref.jobFolderName, material, listOf(11, 3, 6)))
        } }
        compose.onNodeWithTag("cnc-navigator-loaded-11").assertExists()
        compose.onNodeWithTag("cnc-navigator-loaded-3").assertDoesNotExist()
        compose.onNodeWithText("ON CNC").assertExists()
        compose.runOnIdle { marks.value = CncSheetMarks() }
        compose.onNodeWithText("ON CNC").assertDoesNotExist()
        compose.onNodeWithTag("cnc-navigator-loaded-11").assertDoesNotExist()
        assertFalse(file.exists())
    }
}

