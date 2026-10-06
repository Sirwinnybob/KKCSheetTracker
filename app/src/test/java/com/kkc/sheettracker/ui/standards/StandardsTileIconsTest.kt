package com.kkc.sheettracker.ui.standards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardsTileIconsTest {

    @Test
    fun everyTileUsesTheCustomLibraryIconFamily() {
        StandardsTile.entries.forEach { tile ->
            assertTrue("${tile.name} icon ${tile.icon.name}", tile.icon.name.startsWith("Library"))
        }
    }

    @Test
    fun noTwoTilesShareAnIcon() {
        val names = StandardsTile.entries.map { it.icon.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun tileIconsUseThe24UnitViewport() {
        StandardsTile.entries.forEach {
            assertEquals(it.name, 24f, it.icon.viewportWidth)
            assertEquals(it.name, 24f, it.icon.viewportHeight)
        }
    }
}
