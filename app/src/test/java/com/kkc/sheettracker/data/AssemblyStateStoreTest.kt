package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.AssemblyJob
import com.kkc.sheettracker.data.models.CacheIndexCncProgress
import com.kkc.sheettracker.data.models.CacheIndexHardwoodsProgress
import com.kkc.sheettracker.data.models.CacheIndexProgressSummary
import com.kkc.sheettracker.data.unified.UnifiedAssemblySnapshot
import com.kkc.sheettracker.data.unified.UnifiedJobInfo
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class AssemblyStateStoreTest {

    @Test
    fun `deriveJobCards reads through the injected live engine, not a registry lookup`() {
        val liveEngine = mock<UnifiedMetadataEngine> {
            on { getCachedJobInfos() } doReturn listOf(
                UnifiedJobInfo(folderName = "1234 - Job", jobNumber = "1234", jobName = "Job")
            )
            on { getAssemblySnapshot("1234 - Job") } doReturn UnifiedAssemblySnapshot(
                job = AssemblyJob(folderName = "1234 - Job", jobNumber = "1234", jobName = "Job")
            )
            on { getProgressFromIndex("1234 - Job") } doReturn CacheIndexProgressSummary(
                cnc = CacheIndexCncProgress(totalSheets = 4, done = 2),
                hardwoods = CacheIndexHardwoodsProgress(totalPieces = 10, donePieces = 5)
            )
        }
        val store = AssemblyStateStore(
            assemblyScanCoordinator = mock(),
            scanCoordinator = mock(),
            hardwoodsScanCoordinator = mock(),
            progressStore = mock(),
            hardwoodsProgressStore = mock(),
            liveEngine = liveEngine
        )

        val cards = store.deriveJobCards()

        assertEquals(1, cards.size)
        assertEquals("1234 - Job", cards[0].folderName)
        assertEquals(4, cards[0].cncSummary.totalSheets)
        assertEquals(2, cards[0].cncSummary.completedSheets)
        assertEquals(10, cards[0].hardwoodsSummary.totalPieces)
        assertEquals(5, cards[0].hardwoodsSummary.donePieces)
    }
}
