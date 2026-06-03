package com.kkc.sheettracker.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkModeTest {
    @Test
    fun fromStored_returnsCncWhenNull() {
        assertEquals(WorkMode.CNC, WorkMode.fromStored(null))
    }

    @Test
    fun fromStored_returnsModeForKnownValue() {
        assertEquals(WorkMode.SPECIALTY, WorkMode.fromStored("SPECIALTY"))
    }

    @Test
    fun fromStored_isCaseInsensitive() {
        assertEquals(WorkMode.HARDWOODS, WorkMode.fromStored("hardwoods"))
    }

    @Test
    fun fromStored_fallsBackToCncForUnknownValue() {
        assertEquals(WorkMode.CNC, WorkMode.fromStored("not-a-mode"))
    }
}
