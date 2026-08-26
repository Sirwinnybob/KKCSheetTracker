package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.Material
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MixCatalogProjectionTest {
    private val material = Material(pdfFilename = "19mm.pdf", materialName = "19mm", pageCount = 3)

    @Test
    fun `active mixes split one material while history and external mixes do not produce rows`() {
        val catalog = snapshot(
            active("First", "R2.pgm"),
            active("Second", "R1.pgm"),
            history("Old"),
            external("Manual.mix")
        )

        val rows = activeMixRows(material, catalog)

        assertEquals(listOf("First - 19mm", "Second - 19mm"), rows.map { it.title })
        assertEquals(listOf("First", "Second"), rows.map { it.activeMix?.name })
    }

    @Test
    fun `no active mix and one active mix each preserve one material row`() {
        val noActiveRows = activeMixRows(material, snapshot(history("Old"), external("Manual.mix")))
        val oneActiveRows = activeMixRows(material, snapshot(active("Current", "R1.pgm"), history("Old")))

        assertEquals(listOf("19mm"), noActiveRows.map { it.title })
        assertNull(noActiveRows.single().activeMix)
        assertEquals(listOf("19mm"), oneActiveRows.map { it.title })
        assertEquals("Current", oneActiveRows.single().activeMix?.name)
    }

    @Test
    fun `selected active lookup excludes history and external entries`() {
        val catalog = snapshot(active("Current", "R1.pgm"), history("Old"), external("Manual.mix"))

        assertEquals("Current", resolveSelectedActiveMix(catalog, "Current")?.name)
        assertNull(resolveSelectedActiveMix(catalog, "Old"))
        assertNull(resolveSelectedActiveMix(catalog, "Manual.mix"))
        assertNull(resolveSelectedActiveMix(catalog, "Missing"))
    }

    private fun snapshot(vararg entries: MixCatalogEntry) =
        MixCatalogSnapshot(job = "100 - Alpha", material = "19mm", revision = 1, entries = entries.toList())

    private fun active(name: String, vararg programs: String) =
        MixCatalogEntry(name = name, mixFilename = "$name.mix", lifecycle = MixLifecycle.ACTIVE, programs = programs.toList())

    private fun history(name: String) =
        MixCatalogEntry(name = name, mixFilename = "$name.mix", lifecycle = MixLifecycle.HISTORY)

    private fun external(name: String) =
        MixCatalogEntry(name = name, mixFilename = name, lifecycle = MixLifecycle.EXTERNAL)
}
