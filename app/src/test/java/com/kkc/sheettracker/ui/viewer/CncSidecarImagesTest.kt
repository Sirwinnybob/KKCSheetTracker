package com.kkc.sheettracker.ui.viewer

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.MaterialMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CncSidecarImagesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun companionPathsPreferExplicitJxlAndSupportLegacyAndJxlOnlyJobs() {
        assertEquals(listOf("new.jxl", "drawing.jxl", "drawing.png"), cncSidecarImagePaths("drawing.png", "new.jxl"))
        assertEquals(listOf("drawing.jxl"), cncSidecarImagePaths("drawing.jxl", null))
        assertEquals(listOf("new.jxl"), cncSidecarImagePaths(null, "new.jxl"))
        assertTrue(cncSidecarImagePaths(" ", null).isEmpty())
    }

    @Test fun jxlDrawingLoadsWithoutOpeningOrRequiringThePdf() {
        val pdf = File(temporary.root, "missing.pdf")
        val jxl = File(temporary.root, "drawing.jxl").apply { writeText("jxl") }
        val png = File(temporary.root, "drawing.png").apply { writeText("png") }
        assertTrue(jxl.setLastModified(png.lastModified()))
        val decoded = mutableListOf<String>()
        val result = loadPreferredCncSidecar(pdf, "drawing.png") {
            decoded += it.name
            it.readText()
        }
        assertEquals("jxl", result)
        assertEquals(listOf("drawing.jxl"), decoded)
        assertFalse(pdf.exists())
    }

    @Test fun incompleteOrUnreadableJxlFallsBackToLegacyImage() {
        val pdf = File(temporary.root, "missing.pdf")
        File(temporary.root, "drawing.png").writeText("legacy")
        assertEquals("legacy", loadPreferredCncSidecar(pdf, "drawing.png") { it.readText() })
        File(temporary.root, "drawing.jxl").writeText("incomplete")
        assertEquals("legacy", loadPreferredCncSidecar(pdf, "drawing.png") {
            if (it.extension == "jxl") throw IllegalArgumentException("bad image")
            it.readText()
        })
        assertEquals("legacy", loadPreferredCncSidecar(pdf, "drawing.png") {
            if (it.extension == "jxl") throw UnsatisfiedLinkError("native decoder unavailable")
            it.readText()
        })
    }

    @Test fun jxlMetadataAndDrawingPolicySurviveWithoutLegacyPaths() {
        val page = Gson().fromJson("""{"diagramJxlPath":"drawing.jxl","thumbnailJxlPath":"thumb.jxl"}""", PageMetadata::class.java)
        assertEquals("drawing.jxl", page.diagramJxlPath)
        assertEquals("thumb.jxl", page.thumbnailJxlPath)
        assertEquals(SheetDiagramSource.SIDECAR_DIAGRAM, effectiveDiagramSource(SheetRenderQuality.CURRENT, null, page.diagramJxlPath))
    }

    @Test fun staleJxlDoesNotWinOverANewerLegacyImageDuringSync() {
        val pdf = File(temporary.root, "missing.pdf")
        val jxl = File(temporary.root, "drawing.jxl").apply { writeText("old drawing") }
        File(temporary.root, "drawing.png").writeText("current drawing")
        assertTrue(jxl.setLastModified(1000L))
        assertEquals("current drawing", loadPreferredCncSidecar(pdf, "drawing.png") { it.readText() })
    }

    @Test fun validMetadataDrawingSkipsEmbeddedPdfReadAndThumbnailFallback() {
        assertEquals("drawing", loadCncDrawingForDisplay(
            sidecar = { "drawing" },
            embeddedPdfImage = { error("PDF must not be opened") },
            thumbnail = { error("Full drawing must win") }
        ))
        assertEquals("older-pdf", loadCncDrawingForDisplay(
            sidecar = { null }, embeddedPdfImage = { "older-pdf" }, thumbnail = { "thumbnail" }
        ))
    }

    @Test fun drawingIdentityRefreshesOcrScaleAndImagePathsWithoutChangingPdfFingerprint() {
        val page = PageMetadata(pageNumber = 1, diagramPath = "drawing.png", ocrImageWidth = null)
        val original = MaterialMetadata(pages = listOf(page))
        val initial = cncDrawingMetadataIdentity(original)
        assertNotEquals(initial, cncDrawingMetadataIdentity(original.copy(pages = listOf(page.copy(ocrImageWidth = 5100)))))
        assertNotEquals(initial, cncDrawingMetadataIdentity(original.copy(pages = listOf(page.copy(diagramJxlPath = "drawing.jxl")))))
        // Changes to labels and OCR timestamps must not discard the decoded image.
        assertEquals(initial, cncDrawingMetadataIdentity(original.copy(pages = listOf(page.copy(ocrGeneratedAt = "updated")))))
    }
}
