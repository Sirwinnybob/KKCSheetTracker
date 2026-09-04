package com.kkc.sheettracker.ui.detail

import com.kkc.sheettracker.data.mixservice.MixCatalogEntry
import com.kkc.sheettracker.data.mixservice.MixCatalogSnapshot
import com.kkc.sheettracker.data.mixservice.MixLifecycle
import com.kkc.sheettracker.data.mixservice.MixCatalogFetchResult
import com.kkc.sheettracker.data.mixservice.MaterialMixEntry
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialMetadata
import com.kkc.sheettracker.data.models.PageMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JobDetailLoadStateTest {
    @Test
    fun jobDetailLoadStateSeparatesPendingDataFromAnUnavailableJob() {
        assertEquals(JobDetailLoadState.LOADING, jobDetailLoadState(hasResolved = false, hasJob = false))
        assertEquals(JobDetailLoadState.AVAILABLE, jobDetailLoadState(hasResolved = true, hasJob = true))
        assertEquals(JobDetailLoadState.UNAVAILABLE, jobDetailLoadState(hasResolved = true, hasJob = false))
    }

    @Test
    fun jobDetailLoadKeyIgnoresBackgroundScanGenerationButHonorsJobAndRetry() {
        val firstGeneration = jobDetailLoadKey(
            jobFolderName = "670 - ESCHRICH 1364 VICTORIAN",
            retryAttempt = 0,
            scanGeneration = 41
        )
        val afterTrackerRefresh = jobDetailLoadKey(
            jobFolderName = "670 - ESCHRICH 1364 VICTORIAN",
            retryAttempt = 0,
            scanGeneration = 42
        )
        val manualRetry = jobDetailLoadKey(
            jobFolderName = "670 - ESCHRICH 1364 VICTORIAN",
            retryAttempt = 1,
            scanGeneration = 42
        )
        val differentJob = jobDetailLoadKey(
            jobFolderName = "668 - OTHER JOB",
            retryAttempt = 0,
            scanGeneration = 42
        )

        assertEquals(firstGeneration, afterTrackerRefresh)
        org.junit.Assert.assertNotEquals(firstGeneration, manualRetry)
        org.junit.Assert.assertNotEquals(firstGeneration, differentJob)
    }

    @Test
    fun `active catalog rows produce explicit viewer selections in catalog program order`() {
        val material = Material(
            pdfFilename = "19mm.pdf",
            materialName = "19mm",
            pageCount = 2,
            metadata = MaterialMetadata(
                pages = listOf(
                    PageMetadata(pageNumber = 1, sheetFiles = listOf("R1")),
                    PageMetadata(pageNumber = 2, sheetFiles = listOf("R2"))
                )
            )
        )
        val snapshot = MixCatalogSnapshot(
            job = "100 - Alpha",
            material = "19mm",
            revision = 7L,
            entries = listOf(
                MixCatalogEntry(
                    name = "Current",
                    mixFilename = "Current.mix",
                    lifecycle = MixLifecycle.ACTIVE,
                    programs = listOf("R2.pgm", "R1.pgm")
                ),
                MixCatalogEntry(
                    name = "Old",
                    mixFilename = "Old.mix",
                    lifecycle = MixLifecycle.HISTORY,
                    programs = listOf("R1.pgm")
                ),
                MixCatalogEntry(
                    name = "Manual.mix",
                    mixFilename = "Manual.mix",
                    lifecycle = MixLifecycle.EXTERNAL,
                    programs = listOf("R1.pgm")
                )
            )
        )

        val entries = catalogMaterialEntries(material, snapshot)

        assertEquals(1, entries.size)
        assertEquals(listOf(2, 1), entries.single().mixSelection?.pageOrder)
        assertEquals("19mm", entries.single().title)
    }

    @Test
    fun `history and external catalog rows never create viewer cards`() {
        val material = Material(pdfFilename = "19mm.pdf", materialName = "19mm", pageCount = 2)
        val snapshot = MixCatalogSnapshot(
            job = "100 - Alpha",
            material = "19mm",
            revision = 7L,
            entries = listOf(
                MixCatalogEntry("Old", "Old.mix", MixLifecycle.HISTORY, programs = listOf("R1.pgm")),
                MixCatalogEntry("Manual.mix", "Manual.mix", MixLifecycle.EXTERNAL, programs = listOf("R2.pgm"))
            )
        )

        val entries = catalogMaterialEntries(material, snapshot)

        assertEquals(1, entries.size)
        assertEquals("19mm", entries.single().title)
        assertEquals(null, entries.single().mixSelection)
    }

    @Test
    fun `active mix with no mapped pages remains unavailable instead of falling back to all pages`() {
        val material = Material(
            pdfFilename = "19mm.pdf",
            materialName = "19mm",
            pageCount = 2,
            metadata = MaterialMetadata(
                pages = listOf(
                    PageMetadata(pageNumber = 1, sheetFiles = listOf("R1")),
                    PageMetadata(pageNumber = 2, sheetFiles = listOf("R2")),
                ),
            ),
        )
        val snapshot = MixCatalogSnapshot(
            job = "100 - Alpha",
            material = "19mm",
            revision = 8L,
            entries = listOf(
                MixCatalogEntry("Broken", "Broken.mix", MixLifecycle.ACTIVE, listOf("Missing.pgm")),
            ),
        )

        val entry = catalogMaterialEntries(material, snapshot).single()

        assertEquals("Broken", entry.mixSelection?.name)
        assertTrue(entry.mixSelection?.pageOrder?.isEmpty() == true)
        assertEquals(false, canOpenCatalogMaterialEntry(entry))
    }

    @Test
    fun `catalog refresh failure marks cached detail stale and uncached detail unavailable`() {
        val cached = MixCatalogSnapshot(
            job = "100 - Alpha",
            material = "19mm",
            revision = 7L,
            entries = emptyList(),
        )
        val stale = jobDetailCatalogStateAfterRefresh(
            previous = JobDetailCatalogState(),
            materialName = "19mm",
            cached = cached,
            refreshed = MixCatalogFetchResult.NetworkError,
        )
        val unavailable = jobDetailCatalogStateAfterRefresh(
            previous = JobDetailCatalogState(),
            materialName = "19mm",
            cached = null,
            refreshed = MixCatalogFetchResult.NetworkError,
        )

        assertEquals(cached, stale.snapshots["19mm"])
        assertEquals(JobDetailCatalogStatus.STALE, stale.statuses["19mm"])
        assertEquals(JobDetailCatalogStatus.UNAVAILABLE, unavailable.statuses["19mm"])
        val unscoped = MaterialMixEntry(
            material = Material(pdfFilename = "19mm.pdf", materialName = "19mm", pageCount = 2),
            title = "19mm",
            mixSelection = null,
        )
        assertEquals(false, canOpenCatalogMaterialEntry(unscoped, JobDetailCatalogStatus.UNAVAILABLE))
    }
}
