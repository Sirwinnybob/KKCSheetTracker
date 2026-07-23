package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafetySubscriptionManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testNotificationCountForSubscriber() {
        val repo = SafetyRepository(tempFolder.root.absolutePath)
        repo.addConcern("Alice", "Test Hazard", "Near Miss", "Test detail", emptyList(), "t1")

        val manager = SafetySubscriptionManager(repo)
        val count = manager.calculateNotificationCount(isSubscriber = true, lastSeenCount = 0)
        assertEquals(1, count)

        val nonSubCount = manager.calculateNotificationCount(isSubscriber = false, lastSeenCount = 0)
        assertEquals(0, nonSubCount)
    }
}
