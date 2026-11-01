package com.kkc.sheettracker.ui.detail

import com.kkc.sheettracker.data.models.Material
import org.junit.Assert.assertEquals
import org.junit.Test

class JobDetailLoadStateTest {
    @Test
    fun disabledArchiveMixCatalogUsesNaturalRowsWithoutRecoveryNotice() {
        val material = Material(pdfFilename = "19mm.pdf", materialName = "19mm", pageCount = 2)
        val row = jobDetailMixRows(material, null) { com.kkc.sheettracker.data.models.SheetStatus.NOT_STARTED }.single()
        assertEquals("19mm", row.title)
        assertEquals(null, row.selection)
        assertEquals(JobDetailCatalogStatus.FRESH, jobDetailCatalogStatus(enabled = false, state = null))
    }

    @Test
    fun enabledCatalogKeepsOfflineAndOutdatedRecovery() {
        assertEquals(JobDetailCatalogStatus.UNAVAILABLE, jobDetailCatalogStatus(true, null))
        assertEquals(JobDetailCatalogStatus.STALE, jobDetailCatalogStatus(true, com.kkc.sheettracker.data.mixfiles.CatalogState.Offline(null)))
        assertEquals(JobDetailCatalogStatus.UNAVAILABLE, jobDetailCatalogStatus(true, com.kkc.sheettracker.data.mixfiles.CatalogState.Outdated(null)))
    }

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

}
