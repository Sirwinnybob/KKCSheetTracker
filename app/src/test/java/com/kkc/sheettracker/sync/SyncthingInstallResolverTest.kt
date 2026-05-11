package com.kkc.sheettracker.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncthingInstallResolverTest {

    @Test
    fun `fork package maps to fork actions`() {
        val packageName = "com.github.catfriend1.syncthingfork"
        val target = SyncthingInstallTarget(
            packageName = packageName,
            startAction = "$packageName.action.START",
            stopAction = "$packageName.action.STOP"
        )

        assertEquals("com.github.catfriend1.syncthingfork.action.START", target.startAction)
        assertEquals("com.github.catfriend1.syncthingfork.action.STOP", target.stopAction)
    }
}
