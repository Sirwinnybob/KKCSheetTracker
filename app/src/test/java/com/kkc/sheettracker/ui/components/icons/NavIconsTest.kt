package com.kkc.sheettracker.ui.components.icons

import com.kkc.sheettracker.ui.components.NavDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavIconsTest {

    @Test
    fun everyDestinationHasDistinctSelectedAndUnselectedIcons() {
        NavDestination.entries.forEach { dest ->
            assertNotEquals(
                "${dest.name} selected and unselected icons must differ",
                dest.selectedIcon.name,
                dest.unselectedIcon.name
            )
        }
    }

    @Test
    fun everyDestinationUsesTheCustomNavIconFamily() {
        NavDestination.entries.forEach { dest ->
            assertTrue("${dest.name} selected", dest.selectedIcon.name.startsWith("Nav"))
            assertTrue("${dest.name} unselected", dest.unselectedIcon.name.startsWith("Nav"))
        }
    }

    @Test
    fun noTwoDestinationsShareAnIcon() {
        val names = NavDestination.entries.flatMap { listOf(it.selectedIcon.name, it.unselectedIcon.name) }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun calculatorIconsDifferByState() {
        assertNotEquals(NavCalculatorSelected.name, NavCalculatorUnselected.name)
    }

    @Test
    fun allIconsUseThe24UnitViewport() {
        val all = NavDestination.entries.flatMap { listOf(it.selectedIcon, it.unselectedIcon) } +
            listOf(NavCalculatorSelected, NavCalculatorUnselected)
        all.forEach {
            assertEquals(it.name, 24f, it.viewportWidth)
            assertEquals(it.name, 24f, it.viewportHeight)
        }
    }
}
