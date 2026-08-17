package com.kkc.sheettracker.ui.jobs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedJobsScreenTest {
    @Test
    fun backgroundWorkRunsOnlyForTheActiveTab() {
        assertTrue(shouldRunUnifiedJobsBackgroundWork(active = true))
        assertFalse(shouldRunUnifiedJobsBackgroundWork(active = false))
    }

    @Test
    fun labelLoadingRunsOnlyForTheActiveTab() {
        assertTrue(shouldLoadUnifiedJobsLabels(active = true))
        assertFalse(shouldLoadUnifiedJobsLabels(active = false))
    }
}
