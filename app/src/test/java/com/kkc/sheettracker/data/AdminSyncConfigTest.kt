package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdminSyncConfigTest {

    @Test
    fun `builds url from a plain ip`() {
        assertEquals("http://192.168.1.20:5002", buildAdminSyncUrl("192.168.1.20"))
    }

    @Test
    fun `trims whitespace around the ip`() {
        assertEquals("http://192.168.1.20:5002", buildAdminSyncUrl("  192.168.1.20  "))
    }

    @Test
    fun `returns null for null input`() {
        assertNull(buildAdminSyncUrl(null))
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(buildAdminSyncUrl("   "))
    }
}
