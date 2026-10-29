package com.kkc.sheettracker.ui.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.cnc.*
import com.kkc.sheettracker.data.models.*
import com.kkc.sheettracker.ui.components.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CncLiveDashboardTest {
    @get:Rule val compose = createComposeRule()
    private val ref = CncSheetRef("709 - BRAD TAYLOR", "709 - 19mm Pre_Finished.pdf", 11)
    private val item = DashboardRecentMaterialItem(ref.jobFolderName, "709", "19mm", ref.pdfFilename,
        "fp1", 6, 6, 1L, StatusCounts(total = 11, complete = 5))
    private val projection = CncAppStateProjection(
        CncProgramIndex(thumbnailsBySheet = mapOf(ref to "11.png")),
        mapOf(JobMaterialKey(ref.jobFolderName, ref.pdfFilename) to item))
    @Test fun loadedRowOpensLoadedSheetAndClearsBackToOrdinaryRecents() {
        val marks = mutableStateOf(CncSheetMarks(CncLoadedMark.Matched(ref, "R82010BN", true)))
        var opened: CncSheetRef? = null
        val repository = JobRepository(File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "cnc-ui-empty"))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLowEndMode provides LowEndModeFlags(true, false, false, false, false)) {
                    CncRecentMaterialsSection(listOf(item), true, repository,
                        onOpenSheet = { job, pdf, page -> opened = CncSheetRef(job, pdf, page) },
                        cncProjection = projection, cncMarks = marks.value,
                        onOpenLoadedSheet = { job, pdf, page -> opened = CncSheetRef(job, pdf, page) })
                }
            }
        }
        compose.onNodeWithText("ON CNC").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ref, opened) }
        compose.onNodeWithText("1 recent material").assertExists()
        val before = compose.onNodeWithTag("cnc-status-dot", useUnmergedTree = true).captureToImage()
        compose.mainClock.advanceTimeBy(1_000)
        val after = compose.onNodeWithTag("cnc-status-dot", useUnmergedTree = true).captureToImage()
        val a = before.toPixelMap(); val b = after.toPixelMap()
        assertEquals(a[a.width / 2, a.height / 2], b[b.width / 2, b.height / 2])
        compose.runOnIdle { marks.value = CncSheetMarks() }
        compose.onNodeWithText("ON CNC").assertDoesNotExist()
        compose.onNodeWithText("${ref.jobFolderName} • Next sheet 6").assertExists()
    }
    @Test fun unknownFilenameHasNoClickActionOrProgress() {
        val repository = JobRepository(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        compose.setContent { MaterialTheme {
            CncRecentMaterialsSection(emptyList(), true, repository, onOpenSheet = { _, _, _ -> error("Unknown clicked") },
                cncMarks = CncSheetMarks(CncLoadedMark.Unmatched("UNKNOWN_FILE", false)))
        } }
        compose.onNodeWithText("UNKNOWN_FILE").assertHasNoClickAction()
        compose.onNodeWithText("Loaded · idle").assertExists()
        compose.onNodeWithText("Nothing is in progress right now.").assertDoesNotExist()
        compose.onAllNodes(hasText("complete", substring = true)).assertCountEquals(0)
    }
}

