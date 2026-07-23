package com.kkc.sheettracker.data.models

import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyModelsTest {
    @Test
    fun testSafetyStatusRecencyOrdering() {
        val r1 = SafetyStatusRecord(status = "OPEN", by = "UserA", at = "2026-07-23T10:00:00Z")
        val r2 = SafetyStatusRecord(status = "ACKNOWLEDGED", by = "Admin", at = "2026-07-23T10:05:00Z")
        val list = listOf(r1, r2)
        assertEquals("ACKNOWLEDGED", list.maxByOrNull { it.at }?.status)
    }
}
