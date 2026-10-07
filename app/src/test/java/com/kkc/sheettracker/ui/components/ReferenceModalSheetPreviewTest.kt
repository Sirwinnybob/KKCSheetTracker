package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceModalSheetPreviewTest {

    @Test
    fun `splitter sheet image wins and later sources are never loaded`() {
        val calls = mutableListOf<String>()
        val result = resolveSheetPreview(
            sidecarDiagram = { calls += "diagram"; "diagram.png" },
            embeddedImage = { calls += "embedded"; "embedded" },
            sidecarThumbnail = { calls += "thumb"; "thumb.png" },
            fullPageRender = { calls += "render"; "render" }
        )
        assertEquals("diagram.png", result)
        assertEquals(listOf("diagram"), calls)
    }

    @Test
    fun `falls back in viewer order when earlier sources are missing`() {
        assertEquals(
            "embedded",
            resolveSheetPreview({ null }, { "embedded" }, { "thumb.png" }, { "render" })
        )
        assertEquals(
            "thumb.png",
            resolveSheetPreview({ null }, { null }, { "thumb.png" }, { "render" })
        )
        assertEquals(
            "render",
            resolveSheetPreview({ null }, { null }, { null }, { "render" })
        )
    }

    @Test
    fun `nothing available gives null`() {
        assertNull(resolveSheetPreview<String>({ null }, { null }, { null }, { null }))
    }
}
