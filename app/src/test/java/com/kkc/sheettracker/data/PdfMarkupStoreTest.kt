package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.data.models.PdfMarkupPageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PdfMarkupStoreTest {
    private val jobFolderName = "1234 - Test Job"

    @Test
    fun mergedActiveStrokes_filtersDeletedIdsAcrossTabletFilesForSamePdfPage() {
        val baseDir = createTempBaseDir()

        val tabletA = PdfMarkupStore(baseDir, "tablet-a")
        tabletA.savePageMarkup(
            jobFolderName = jobFolderName,
            pdfFilename = "Plans.pdf",
            page = 2,
            strokes = listOf(
                stroke(id = "keep-a"),
                stroke(id = "delete-me")
            ),
            deletedStrokeIds = emptyList()
        )

        val tabletB = PdfMarkupStore(baseDir, "tablet-b")
        tabletB.savePageMarkup(
            jobFolderName = jobFolderName,
            pdfFilename = "Plans.pdf",
            page = 2,
            strokes = listOf(stroke(id = "keep-b")),
            deletedStrokeIds = listOf("delete-me")
        )

        val merged = tabletA.getMergedActiveStrokes(jobFolderName, "Plans.pdf", 2)

        assertEquals(listOf("keep-a", "keep-b"), merged.map { it.id })
    }

    @Test
    fun mergedActiveStrokes_keepsDeletesScopedToExactPdfFilenameAndPage() {
        val baseDir = createTempBaseDir()

        val tabletA = PdfMarkupStore(baseDir, "tablet-a")
        tabletA.savePageMarkup(
            jobFolderName = jobFolderName,
            pdfFilename = "Plans.pdf",
            page = 1,
            strokes = listOf(stroke(id = "shared-id")),
            deletedStrokeIds = emptyList()
        )

        val tabletB = PdfMarkupStore(baseDir, "tablet-b")
        tabletB.savePageMarkup(
            jobFolderName = jobFolderName,
            pdfFilename = "Plans.pdf",
            page = 2,
            strokes = emptyList(),
            deletedStrokeIds = listOf("shared-id")
        )
        tabletB.savePageMarkup(
            jobFolderName = jobFolderName,
            pdfFilename = "plans.pdf",
            page = 1,
            strokes = emptyList(),
            deletedStrokeIds = listOf("shared-id")
        )

        val merged = tabletA.getMergedActiveStrokes(jobFolderName, "Plans.pdf", 1)

        assertEquals(listOf("shared-id"), merged.map { it.id })
    }

    @Test
    fun savePageMarkup_preservesOtherPagesInSameTabletFile() {
        val baseDir = createTempBaseDir()
        val store = PdfMarkupStore(baseDir, "tablet-a")

        store.savePageMarkup(
            jobFolderName = jobFolderName,
            pdfFilename = "Plans.pdf",
            page = 1,
            strokes = listOf(stroke(id = "page-1")),
            deletedStrokeIds = emptyList()
        )
        store.savePageMarkup(
            jobFolderName = jobFolderName,
            pdfFilename = "Plans.pdf",
            page = 2,
            strokes = listOf(stroke(id = "page-2")),
            deletedStrokeIds = emptyList()
        )

        val markup = store.loadTabletMarkup(jobFolderName)

        assertEquals(2, markup.pages.size)
        assertTrue(markup.pages.any { it.pdfFilename == "Plans.pdf" && it.page == 1 && it.strokes.map(PdfInkStroke::id) == listOf("page-1") })
        assertTrue(markup.pages.any { it.pdfFilename == "Plans.pdf" && it.page == 2 && it.strokes.map(PdfInkStroke::id) == listOf("page-2") })
    }

    @Test
    fun mergedActiveStrokesByPage_groupsResultsByExactPdfFilenameAndPage() {
        val baseDir = createTempBaseDir()

        val tabletA = PdfMarkupStore(baseDir, "tablet-a")
        tabletA.savePageMarkup(
            jobFolderName = jobFolderName,
            pdfFilename = "Plans.pdf",
            page = 1,
            strokes = listOf(stroke(id = "plans-1")),
            deletedStrokeIds = emptyList()
        )
        tabletA.savePageMarkup(
            jobFolderName = jobFolderName,
            pdfFilename = "Assembly.pdf",
            page = 1,
            strokes = listOf(stroke(id = "assembly-1")),
            deletedStrokeIds = emptyList()
        )

        val merged = tabletA.getMergedActiveStrokesByPage(jobFolderName)

        assertEquals(listOf("plans-1"), merged[PdfMarkupPageKey("Plans.pdf", 1)]?.map { it.id })
        assertEquals(listOf("assembly-1"), merged[PdfMarkupPageKey("Assembly.pdf", 1)]?.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun savePageMarkup_rejectsJobFolderPathEscape() {
        val baseDir = createTempBaseDir()
        val store = PdfMarkupStore(baseDir, "tablet-a")

        store.savePageMarkup(
            jobFolderName = "..\\outside",
            pdfFilename = "Plans.pdf",
            page = 1,
            strokes = listOf(stroke(id = "x")),
            deletedStrokeIds = emptyList()
        )
    }

    @Test
    fun loadTabletMarkup_toleratesLegacyNullCollections() {
        val baseDir = createTempBaseDir()
        val trackerDir = File(baseDir, "$jobFolderName/.metadata/pdf_markup/.tracker").apply { mkdirs() }
        File(trackerDir, "tablet-a.json").writeText(
            """
            {
              "tabletId": "tablet-a",
              "pages": [
                {
                  "pdfFilename": "Plans.pdf",
                  "page": 1,
                  "strokes": null,
                  "deletedStrokeIds": null
                }
              ]
            }
            """.trimIndent()
        )

        val store = PdfMarkupStore(baseDir, "tablet-a")
        val markup = store.loadTabletMarkup(jobFolderName)

        assertEquals(1, markup.pages.size)
        assertTrue(markup.pages.first().strokes.isEmpty())
        assertTrue(markup.pages.first().deletedStrokeIds.isEmpty())
    }

    @Test
    fun mergedActiveStrokes_prefersNewerTabletFileForSameStrokeId() {
        val baseDir = createTempBaseDir()

        val tabletA = PdfMarkupStore(baseDir, "tablet-a")
        tabletA.savePageMarkup(
            jobFolderName = jobFolderName,
            pdfFilename = "Plans.pdf",
            page = 1,
            strokes = listOf(stroke(id = "same-id", color = 0xFF0000)),
            deletedStrokeIds = emptyList()
        )

        Thread.sleep(5)

        val tabletB = PdfMarkupStore(baseDir, "tablet-b")
        tabletB.savePageMarkup(
            jobFolderName = jobFolderName,
            pdfFilename = "Plans.pdf",
            page = 1,
            strokes = listOf(stroke(id = "same-id", color = 0x00FF00)),
            deletedStrokeIds = emptyList()
        )

        val merged = tabletA.getMergedActiveStrokes(jobFolderName, "Plans.pdf", 1)

        assertEquals(1, merged.size)
        assertEquals(0x00FF00, merged.first().color)
    }

    private fun stroke(id: String, color: Int = 0xFF0000): PdfInkStroke {
        return PdfInkStroke(
            id = id,
            color = color,
            lineWidth = 4.0f,
            isHighlighter = false,
            points = listOf(0f, 1f, 2f, 3f)
        )
    }

    private fun createTempBaseDir(): File {
        return Files.createTempDirectory("pdf-markup-store-test").toFile()
    }
}
