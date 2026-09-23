package com.kkc.sheettracker.ui.markup

import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.data.models.PdfMarkupPageKey
import com.kkc.sheettracker.data.models.PdfPageMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfMarkupPageStatesTest {
    private val pageA = pdfMarkupPageKey("Plans.pdf", 1)
    private val pageB = pdfMarkupPageKey("Plans.pdf", 2)

    private val persisted = mutableListOf<Pair<PdfMarkupPageKey, PdfMarkupPageSnapshot>>()
    private val pendingSaves = mutableListOf<() -> Unit>()
    private val states = PdfMarkupPageStates { key, snapshot, onSaved ->
        persisted += key to snapshot
        pendingSaves += onSaved
    }

    private fun landAllSaves() {
        pendingSaves.forEach { it() }
        pendingSaves.clear()
    }

    private fun stroke(id: String) =
        PdfInkStroke(id = id, points = listOf(0.1f, 0.1f, 0.2f, 0.2f))

    private fun ids(key: PdfMarkupPageKey) = states.strokesFor(key).map { it.id }

    @Test
    fun `page key trims and lowercases filename`() {
        assertEquals(PdfMarkupPageKey("plans.pdf", 3), pdfMarkupPageKey("  Plans.PDF ", 3))
    }

    @Test
    fun `add stores on its own page and persists only that page`() {
        states.add(pageB, stroke("s1"))

        assertEquals(listOf("s1"), ids(pageB))
        assertEquals(emptyList<String>(), ids(pageA))
        assertEquals(1, persisted.size)
        assertEquals(pageB, persisted.single().first)
        assertEquals(listOf("s1"), persisted.single().second.visibleStrokes.map { it.id })
    }

    @Test
    fun `strokes on different pages are independent`() {
        states.add(pageA, stroke("a1"))
        states.add(pageB, stroke("b1"))
        states.add(pageA, stroke("a2"))

        assertEquals(listOf("a1", "a2"), ids(pageA))
        assertEquals(listOf("b1"), ids(pageB))
    }

    @Test
    fun `erase hides stroke and persists deleted id`() {
        states.add(pageA, stroke("a1"))
        persisted.clear()

        assertTrue(states.erase(pageA, "a1"))

        assertEquals(emptyList<String>(), ids(pageA))
        assertEquals(listOf("a1"), persisted.single().second.deletedIds)
        assertEquals(emptyList<String>(), persisted.single().second.visibleStrokes.map { it.id })
    }

    @Test
    fun `erase of unknown stroke does nothing`() {
        states.add(pageA, stroke("a1"))
        persisted.clear()

        assertFalse(states.erase(pageA, "nope"))
        assertFalse(states.erase(pageB, "a1"))

        assertTrue(persisted.isEmpty())
        assertEquals(listOf("a1"), ids(pageA))
    }

    @Test
    fun `invalid page keys are ignored`() {
        states.add(pdfMarkupPageKey("", 1), stroke("x"))
        states.add(pdfMarkupPageKey("Plans.pdf", 0), stroke("y"))

        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `undo pops most recent stroke across pages first`() {
        states.add(pageA, stroke("a1"))
        states.add(pageB, stroke("b1"))

        assertTrue(states.undoLast(fallbackKey = pageA))
        assertEquals(emptyList<String>(), ids(pageB))
        assertEquals(listOf("a1"), ids(pageA))

        assertTrue(states.undoLast(fallbackKey = pageA))
        assertEquals(emptyList<String>(), ids(pageA))

        assertFalse(states.undoLast(fallbackKey = pageA))
    }

    @Test
    fun `undo skips stack entries that were already erased`() {
        states.add(pageA, stroke("a1"))
        states.add(pageB, stroke("b1"))
        states.erase(pageB, "b1")

        assertTrue(states.undoLast(fallbackKey = pageB))

        assertEquals(emptyList<String>(), ids(pageA))
    }

    @Test
    fun `undo falls back to own strokes on the centered page only`() {
        val guard = states.beginReload()
        states.replaceAll(
            mapOf(
                pageA to PdfMarkupPageSnapshot(
                    strokes = listOf(stroke("other"), stroke("mine")),
                    ownStrokeIds = setOf("mine")
                )
            ),
            guard
        )

        assertTrue(states.hasUndo(pageA))
        assertFalse(states.hasUndo(pageB))

        assertTrue(states.undoLast(pageA))
        assertEquals(listOf("other"), ids(pageA))

        assertFalse(states.hasUndo(pageA))
        assertFalse(states.undoLast(pageA))
    }

    @Test
    fun `replaceAll swaps the whole map`() {
        states.add(pageA, stroke("a1"))
        landAllSaves()

        val guard = states.beginReload()
        states.replaceAll(mapOf(pageB to PdfMarkupPageSnapshot(strokes = listOf(stroke("b1")))), guard)

        assertEquals(emptyList<String>(), ids(pageA))
        assertEquals(listOf("b1"), ids(pageB))
    }

    @Test
    fun `clear empties every page`() {
        states.add(pageA, stroke("a1"))
        states.add(pageB, stroke("b1"))

        states.clear()

        assertEquals(emptyList<String>(), ids(pageA))
        assertEquals(emptyList<String>(), ids(pageB))
    }

    @Test
    fun `guarded reload keeps a page whose save has not landed`() {
        states.add(pageA, stroke("a1"))

        val guard = states.beginReload()
        states.replaceAll(
            mapOf(
                pageA to PdfMarkupPageSnapshot(),
                pageB to PdfMarkupPageSnapshot(strokes = listOf(stroke("b-remote")))
            ),
            guard
        )

        assertEquals(listOf("a1"), ids(pageA))
        assertEquals(listOf("b-remote"), ids(pageB))
    }

    @Test
    fun `guarded reload keeps a page edited while the reload was reading`() {
        states.add(pageA, stroke("a1"))
        landAllSaves()

        val guard = states.beginReload()
        states.add(pageA, stroke("a2"))
        states.replaceAll(mapOf(pageA to PdfMarkupPageSnapshot(strokes = listOf(stroke("a1")))), guard)

        assertEquals(listOf("a1", "a2"), ids(pageA))
    }

    @Test
    fun `guarded reload replaces a page whose saves landed before it started`() {
        states.add(pageA, stroke("a1"))
        landAllSaves()

        val guard = states.beginReload()
        states.replaceAll(
            mapOf(pageA to PdfMarkupPageSnapshot(strokes = listOf(stroke("a1"), stroke("remote")))),
            guard
        )

        assertEquals(listOf("a1", "remote"), ids(pageA))
    }

    @Test
    fun `buildPdfMarkupSnapshots merges other tablets strokes with own ids and deletions`() {
        val merged = mapOf(
            pageA to listOf(stroke("other"), stroke("mine")),
            pageB to listOf(stroke("only-other"))
        )
        val ownPages = listOf(
            PdfPageMarkup(
                pdfFilename = "Plans.pdf",
                page = 1,
                strokes = listOf(stroke("mine")),
                deletedStrokeIds = listOf("gone")
            ),
            PdfPageMarkup(
                pdfFilename = "Plans.pdf",
                page = 3,
                strokes = emptyList(),
                deletedStrokeIds = listOf("old")
            )
        )

        val result = buildPdfMarkupSnapshots(merged, ownPages)

        val a = result.getValue(pageA)
        assertEquals(listOf("other", "mine"), a.strokes.map { it.id })
        assertEquals(setOf("mine"), a.ownStrokeIds)
        assertEquals(listOf("gone"), a.deletedIds)

        val b = result.getValue(pageB)
        assertEquals(setOf<String>(), b.ownStrokeIds)
        assertEquals(emptyList<String>(), b.deletedIds)

        val c = result.getValue(pdfMarkupPageKey("Plans.pdf", 3))
        assertEquals(emptyList<String>(), c.strokes.map { it.id })
        assertEquals(listOf("old"), c.deletedIds)
    }
}
