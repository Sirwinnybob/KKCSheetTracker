package com.kkc.sheettracker.ui.detail

import org.junit.Assert.assertEquals
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
}
