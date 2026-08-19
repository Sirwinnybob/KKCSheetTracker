package com.kkc.sheettracker.data.unified

import com.kkc.sheettracker.data.models.CacheIndexJobInfo
import com.kkc.sheettracker.data.models.CacheIndexProgressSummary
import com.kkc.sheettracker.data.models.CacheIndexRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LiveAwareUnifiedMetadataEngineTest {

    private fun rootFor(folderName: String, jobNumber: String, lineupPosition: Int? = null) = CacheIndexRoot(
        jobInfo = CacheIndexJobInfo(
            folderName = folderName,
            jobNumber = jobNumber,
            jobName = "Job $jobNumber",
            hiddenFromProduction = false,
            lineupPosition = lineupPosition
        ),
        progressSummary = CacheIndexProgressSummary(hasDeliverySheet = true)
    )

    @Test
    fun `listJobsFromCacheIndex returns live data once a snapshot is applied`() {
        val delegate = mock<UnifiedMetadataEngine> {
            on { listJobsFromCacheIndex() } doReturn (listOf(UnifiedJobInfo("stale", "0", "Stale")) to emptyList())
        }
        val engine = LiveAwareUnifiedMetadataEngine(delegate)

        engine.applySnapshot(mapOf("1234 - Job" to rootFor("1234 - Job", "1234")))
        val (jobs, needsDeep) = engine.listJobsFromCacheIndex()

        assertEquals(1, jobs.size)
        assertEquals("1234 - Job", jobs[0].folderName)
        assertTrue(needsDeep.isEmpty())
    }

    @Test
    fun `setConnected false clears live state and falls back to the delegate`() {
        val delegate = mock<UnifiedMetadataEngine> {
            on { getCachedJobInfos() } doReturn listOf(UnifiedJobInfo("from-delegate", "9", "Delegate Job"))
        }
        val engine = LiveAwareUnifiedMetadataEngine(delegate)
        engine.applySnapshot(mapOf("1234 - Job" to rootFor("1234 - Job", "1234")))

        engine.setConnected(false)
        val jobs = engine.getCachedJobInfos()

        assertEquals(1, jobs.size)
        assertEquals("from-delegate", jobs[0].folderName)
    }

    @Test
    fun `applyDelta upserts and removes individual jobs`() {
        val delegate = mock<UnifiedMetadataEngine>()
        val engine = LiveAwareUnifiedMetadataEngine(delegate)
        engine.applySnapshot(mapOf("1234 - Job" to rootFor("1234 - Job", "1234")))

        engine.applyDelta("5678 - Other", rootFor("5678 - Other", "5678"))
        assertEquals(2, engine.getCachedJobInfos().size)

        engine.applyDelta("1234 - Job", null)
        val jobs = engine.getCachedJobInfos()
        assertEquals(1, jobs.size)
        assertEquals("5678 - Other", jobs[0].folderName)
    }

    @Test
    fun `getProgressFromIndex prefers live data when connected, else delegates`() {
        val delegate = mock<UnifiedMetadataEngine> {
            on { getProgressFromIndex("1234 - Job") } doReturn null
        }
        val engine = LiveAwareUnifiedMetadataEngine(delegate)
        engine.applySnapshot(mapOf("1234 - Job" to rootFor("1234 - Job", "1234")))

        assertEquals(true, engine.getProgressFromIndex("1234 - Job")?.hasDeliverySheet)

        engine.setConnected(false)
        assertNull(engine.getProgressFromIndex("1234 - Job"))
    }

    @Test
    fun `unrelated interface methods pass straight through to the delegate`() {
        val delegate = mock<UnifiedMetadataEngine>()
        val engine = LiveAwareUnifiedMetadataEngine(delegate)

        engine.invalidateJob("1234 - Job")

        verify(delegate).invalidateJob("1234 - Job")
    }

    @Test
    fun `live jobs sort by lineup position then job number descending then folder name`() {
        val delegate = mock<UnifiedMetadataEngine>()
        val engine = LiveAwareUnifiedMetadataEngine(delegate)

        engine.applySnapshot(
            mapOf(
                "2000 - B" to rootFor("2000 - B", "2000", lineupPosition = null),
                "1000 - A" to rootFor("1000 - A", "1000", lineupPosition = null),
                "0500 - Pinned" to rootFor("0500 - Pinned", "0500", lineupPosition = 0)
            )
        )

        val (jobs, _) = engine.listJobsFromCacheIndex()

        assertEquals(listOf("0500 - Pinned", "2000 - B", "1000 - A"), jobs.map { it.folderName })
    }
}
