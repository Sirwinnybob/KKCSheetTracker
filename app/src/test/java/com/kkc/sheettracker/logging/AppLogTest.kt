package com.kkc.sheettracker.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test
    fun release_disablesDebugAndInfo() {
        assertFalse(shouldEmitAppLog(AppLogPriority.DEBUG, isDebugBuild = false))
        assertFalse(shouldEmitAppLog(AppLogPriority.INFO, isDebugBuild = false))
    }

    @Test
    fun release_retainsWarningsAndErrors() {
        assertTrue(shouldEmitAppLog(AppLogPriority.WARN, isDebugBuild = false))
        assertTrue(shouldEmitAppLog(AppLogPriority.ERROR, isDebugBuild = false))
    }

    @Test
    fun debugBuild_emitsEverySupportedPriority() {
        AppLogPriority.entries.forEach { priority ->
            assertTrue(shouldEmitAppLog(priority, isDebugBuild = true))
        }
    }
}
