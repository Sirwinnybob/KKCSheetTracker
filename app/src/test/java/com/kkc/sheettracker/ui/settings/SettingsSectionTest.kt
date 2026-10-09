package com.kkc.sheettracker.ui.settings

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionTest {

    @Test
    fun sectionsAreInRailOrderWithFourAdvancedAtTheEnd() {
        assertEquals(
            listOf("LOOK_AND_FEEL", "VIEWERS", "ME", "UPDATES_ABOUT", "TABLET_DATA", "SYNC_NETWORK", "PERFORMANCE_POWER", "ADMIN"),
            SettingsSection.entries.map { it.name }
        )
        assertEquals(listOf(false, false, false, false, true, true, true, true), SettingsSection.entries.map { it.isAdvanced })
    }

    @Test
    fun everySectionHasTitleAndSubtitle() {
        SettingsSection.entries.forEach {
            assertTrue(it.name, it.title.isNotBlank())
            assertTrue(it.name, it.subtitle.isNotBlank())
        }
    }

    @Test
    fun newPendingUpdatesJumpToUpdates() {
        assertEquals(SettingsSection.UPDATES_ABOUT, sectionOnOpen(SettingsSection.ADMIN, "kkc.apk", lastJumpedSignature = null))
        assertEquals(SettingsSection.UPDATES_ABOUT, sectionOnOpen(SettingsSection.ADMIN, "kkc.apk|HT@2", lastJumpedSignature = "kkc.apk"))
    }

    @Test
    fun alreadySurfacedUpdatesDoNotJumpAgain() {
        // e.g. returning from Specialty viewer defaults with the same update still pending
        assertEquals(SettingsSection.VIEWERS, sectionOnOpen(SettingsSection.VIEWERS, "kkc.apk", lastJumpedSignature = "kkc.apk"))
    }

    @Test
    fun noPendingUpdatesKeepsSection() {
        assertEquals(SettingsSection.ADMIN, sectionOnOpen(SettingsSection.ADMIN, updatesSignature = null, lastJumpedSignature = "kkc.apk"))
    }

    @Test
    fun updatesSignatureIsStableAndNullWhenNothingPending() {
        assertEquals(null, updatesSignature(null, emptyList()))
        assertEquals(
            updatesSignature("kkc.apk", listOf("VNC" to "1.2", "HT" to "3.0")),
            updatesSignature("kkc.apk", listOf("HT" to "3.0", "VNC" to "1.2"))
        )
        assertTrue(updatesSignature(null, listOf("HT" to "3.0")) != updatesSignature(null, listOf("HT" to "3.1")))
    }

    @Test
    fun opensOnUpdatesOnlyWhenUpdatesArePending() {
        assertEquals(SettingsSection.UPDATES_ABOUT, initialSection(hasPendingUpdates = true))
        assertEquals(SettingsSection.LOOK_AND_FEEL, initialSection(hasPendingUpdates = false))
    }

    @Test
    fun wideChipsStartAt1000dp() {
        assertFalse(showWideChips(999f))
        assertTrue(showWideChips(1000f))
        assertFalse("portrait shop tablet", showWideChips(824f))
        assertTrue("landscape shop tablet", showWideChips(1318f))
    }

    @Test
    fun modeTileIsSquareUntil168dpThenCapped() {
        assertEquals(150.dp, modeTileHeight(150.dp))
        assertEquals(168.dp, modeTileHeight(168.dp))
        assertEquals(168.dp, modeTileHeight(315.dp))
    }
}
