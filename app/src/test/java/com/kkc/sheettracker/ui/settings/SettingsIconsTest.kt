package com.kkc.sheettracker.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIconsTest {

    private val all = SettingsSection.entries.flatMap { listOf(it.icon(true), it.icon(false)) }

    @Test
    fun selectedAndUnselectedDifferForEverySection() {
        SettingsSection.entries.forEach {
            assertNotEquals(it.name, it.icon(true).name, it.icon(false).name)
        }
    }

    @Test
    fun iconsBelongToTheSettingsFamily() {
        all.forEach { assertTrue(it.name, it.name.startsWith("Settings")) }
    }

    @Test
    fun noTwoSectionsShareAnIcon() {
        val names = all.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun allIconsUseThe24UnitViewport() {
        all.forEach {
            assertEquals(it.name, 24f, it.viewportWidth)
            assertEquals(it.name, 24f, it.viewportHeight)
        }
    }
}
