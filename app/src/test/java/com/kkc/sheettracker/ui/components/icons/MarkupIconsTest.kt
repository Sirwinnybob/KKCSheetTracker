package com.kkc.sheettracker.ui.components.icons

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkupIconsTest {

    private val all = listOf(
        MarkupPenIcon,
        MarkupHighlighterIcon,
        MarkupEraserIcon,
        MarkupUndoIcon,
        MarkupClearIcon,
        MarkupFingerDrawIcon
    )

    @Test
    fun markupIconsAreDistinct() {
        assertEquals(all.size, all.map { it.name }.toSet().size)
    }

    @Test
    fun markupIconsUseThe24UnitViewport() {
        all.forEach {
            assertEquals(it.name, 24f, it.viewportWidth)
            assertEquals(it.name, 24f, it.viewportHeight)
        }
    }
}
