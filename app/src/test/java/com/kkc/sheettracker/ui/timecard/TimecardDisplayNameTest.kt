package com.kkc.sheettracker.ui.timecard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AUD-11: display-name precedence — Hours custom name, then hub effective name, then real
 * name (represented as the empty override so the UI falls back to the real name).
 */
class TimecardDisplayNameTest {

    @Test
    fun customNameWinsOverHubName() {
        assertEquals("Boss", TimecardStore.resolveDisplayOverride("Boss", "Hubby"))
    }

    @Test
    fun hubNameUsedWhenNoCustomName() {
        assertEquals("Hubby", TimecardStore.resolveDisplayOverride(null, "Hubby"))
    }

    @Test
    fun noOverrideWhenBothAbsent() {
        assertEquals("", TimecardStore.resolveDisplayOverride(null, null))
    }

    @Test
    fun noOverrideWhenHubNameEmpty() {
        // Hub returns "" when the effective name equals the real name.
        assertEquals("", TimecardStore.resolveDisplayOverride(null, ""))
    }

    @Test
    fun blankCustomNameFallsThroughToHubName() {
        assertEquals("Hubby", TimecardStore.resolveDisplayOverride("   ", "Hubby"))
    }

    @Test
    fun blankCustomAndBlankHubYieldNoOverride() {
        assertEquals("", TimecardStore.resolveDisplayOverride("  ", "  "))
    }

    @Test
    fun customNameTrimmed() {
        assertEquals("Boss", TimecardStore.resolveDisplayOverride("  Boss  ", null))
    }
}
