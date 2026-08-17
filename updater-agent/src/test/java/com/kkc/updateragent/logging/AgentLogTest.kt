package com.kkc.updateragent.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLogTest {
    @Test
    fun release_disablesInfo() {
        assertFalse(shouldEmitAgentLog(isDebugBuild = false))
    }

    @Test
    fun debugBuild_emitsInfo() {
        assertTrue(shouldEmitAgentLog(isDebugBuild = true))
    }
}
