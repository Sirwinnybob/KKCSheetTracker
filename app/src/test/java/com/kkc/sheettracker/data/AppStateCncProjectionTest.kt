package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.cnc.*
import com.kkc.sheettracker.data.models.*
import com.kkc.sheettracker.data.unified.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class AppStateCncProjectionTest {
    private class Fixture(val test: TestScope, enabled: Boolean = true) {
        var job = cncTestJob()
        var roster = listOf(job.cncInfo())
        var reads = 0
        val files = Files.createTempDirectory("cnc-app-state").toFile()
        val progress = ProgressStore(files, "test", files.resolve(".local"))
        init {
            files.resolve(job.folderName).resolve(".metadata").mkdirs() // Live-job write guard.
            progress.getMaterialLastTouches(job.folderName) // Cold-cache reads increment the existing revision.
        }
        val state = MutableStateFlow(ScanSnapshotState(status = ScanStatus.READY, snapshot = ScanSnapshot(generation = 1)))
        val engine = mock<UnifiedMetadataEngine> {
            on { getCachedJobInfos() } doAnswer { roster }
            on { getCncSnapshot(any()) } doAnswer { call ->
                reads++
                job.takeIf { it.folderName == call.getArgument<String>(0) }?.let { UnifiedCncSnapshot(it, emptyList()) }
            }
        }
        val scan = mock<ScanCoordinator> {
            on { this.state } doReturn state
            on { unifiedEngine } doReturn engine
        }
        val store = AppStateStore(scan, progress, machineStatusEnabled = enabled,
            dispatcher = StandardTestDispatcher(test.testScheduler), scope = test.backgroundScope)
        fun hint() = store.setCncProgramHints(cncProgramHints(MachineStatus.Live(
            LoadedProgram("R82010BN", jobFolder = job.folderName), null)))
        fun bytes() = files.walkTopDown().filter { it.isFile }.associate { it.relativeTo(files).path to it.readBytes().toList() }
    }
    private fun TestScope.settle() { runCurrent(); advanceTimeBy(121); runCurrent() }

    @Test fun untouchedLoadedMaterialHydratesWithoutRecentTouchOrWrites() = runTest {
        val f = Fixture(this); settle(); assertEquals(0, f.reads)
        val before = f.bytes(); val version = f.progress.progressVersion.value
        f.hint(); settle()
        assertEquals(1, f.reads)
        val projection = f.store.cncProjection.value
        val mark = matchCncSheets(MachineStatus.Live(LoadedProgram("R82010BN", jobFolder = f.job.folderName), null), projection.programIndex)
        assertEquals(11, (mark.loaded as CncLoadedMark.Matched).sheet.page)
        assertEquals(0, f.store.dashboardUiModel.value.recentInProgressMaterials.size)
        assertEquals(1, projection.materialRows.values.single().counts.total)
        assertTrue(f.progress.getLocalMaterialLastTouches(f.job.folderName).isEmpty())
        assertEquals(version, f.progress.progressVersion.value)
        assertEquals(before, f.bytes())
        f.hint(); settle(); assertEquals(1, f.reads)
    }
    @Test fun completeLoadedMaterialUsesRealCountsAndProgressDoesNotRebuildIndex() = runTest {
        val f = Fixture(this); f.hint(); settle()
        val beforeIndex = f.store.cncProjection.value.programIndex
        val material = f.job.materials.single()
        f.progress.markSheetComplete(f.job.folderName, material.pdfFilename, 11, material.fileFingerprint)
        settle()
        assertEquals(1, f.store.cncProjection.value.materialRows.values.single().counts.complete)
        assertSame(beforeIndex, f.store.cncProjection.value.programIndex)
    }
    @Test fun sourceRefreshReplacesIndexAndThumbnailAndRemovedActiveJobDropsMarks() = runTest {
        val f = Fixture(this); f.hint(); settle()
        val oldRef = f.store.cncProjection.value.programIndex.thumbnailsBySheet.keys.single()
        val mat = f.job.materials.single()
        f.job = f.job.copy(materials = listOf(mat.copy(metadata = MaterialMetadata(pages =
            listOf(PageMetadata(pageNumber = 10, sheetId = "NEW", thumbnailPath = "new.png"))))))
        f.state.value = f.state.value.copy(snapshot = ScanSnapshot(generation = 2))
        settle()
        val projection = f.store.cncProjection.value
        assertFalse(projection.programIndex.thumbnailsBySheet.containsKey(oldRef))
        assertEquals(listOf("new.png"), projection.programIndex.thumbnailsBySheet.values.toList())
        f.roster = emptyList()
        f.state.value = f.state.value.copy(snapshot = ScanSnapshot(generation = 3))
        settle()
        assertTrue(f.store.cncProjection.value.programIndex.programsByFolder.isEmpty())
    }
    @Test fun archiveScopeHasEmptyProjectionAndIgnoresHints() = runTest {
        val f = Fixture(this, enabled = false); f.hint(); settle()
        assertEquals(CncAppStateProjection(), f.store.cncProjection.value)
        assertEquals(0, f.reads)
    }
}

