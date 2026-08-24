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
}
