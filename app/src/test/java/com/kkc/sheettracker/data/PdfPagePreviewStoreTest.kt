package com.kkc.sheettracker.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PdfPagePreviewStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun cache(parent: File = temporary.root, legacyMarker: Boolean = false): Pair<File, File> {
        parent.mkdirs()
        val pdf = File(parent, "592 - Sheets [A].PDF").apply {
            writeText("PDF contents are never opened by the preview reader")
            check(setLastModified(1_700_000_000_123L))
        }
        val image = File(parent, ".metadata/.fullimages/${pdf.nameWithoutExtension}_p001_render.jxl").apply {
            parentFile!!.mkdirs()
            writeText("JXL")
            check(setLastModified(pdf.lastModified()))
        }
        val markerDirectory = if (legacyMarker) ".fullimages" else ".fullimages-state"
        File(parent, ".metadata/$markerDirectory/${pdf.nameWithoutExtension}.preview.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"policyVersion":${if (legacyMarker) 1 else 2},"sourceFile":"${pdf.name}","source":{"size":${pdf.length()},"mtimeNs":${pdf.lastModified() * 1_000_000 + 987_654}},"pageCount":2,"maxEdge":2560,"dpi":150}""")
        }
        return pdf to image
    }

    @Test fun validJxlIsReadWithoutOpeningThePdfOrWritingFiles() {
        val (pdf, image) = cache()
        val before = temporary.root.walkTopDown().filter { it.isFile }.map { it.relativeTo(temporary.root).path }.toSet()
        assertEquals("JXL", loadPdfPagePreview(pdf, 0) { assertEquals(image, it); it.readText() })
        assertEquals(2, pdfPagePreviewCount(pdf))
        assertEquals(before, temporary.root.walkTopDown().filter { it.isFile }.map { it.relativeTo(temporary.root).path }.toSet())
    }

    @Test fun legacyCompletionMarkersRemainReadableDuringMigration() {
        val (pdf, _) = cache(legacyMarker = true)
        assertEquals("JXL", loadPdfPagePreview(pdf, 0) { it.readText() })
    }

    @Test fun darkAndLightSourcesUseTheirOwnParentCaches() {
        val (light, _) = cache()
        val (dark, darkImage) = cache(File(temporary.root, "DARK MODE"))
        darkImage.writeText("dark")
        assertTrue(darkImage.setLastModified(dark.lastModified()))
        assertEquals("JXL", loadPdfPagePreview(light, 0) { it.readText() })
        assertEquals("dark", loadPdfPagePreview(dark, 0) { it.readText() })
    }

    @Test fun missingEmptyStaleOrOutOfRangePagesUseThePdfFallback() {
        val (pdf, image) = cache()
        val decode: (File) -> String = { error("Invalid preview must not be decoded") }
        assertNull(loadPdfPagePreview(pdf, -1, decode))
        assertNull(loadPdfPagePreview(pdf, 2, decode))
        assertNull(loadPdfPagePreview(pdf, 1, decode))
        image.writeText("")
        assertNull(loadPdfPagePreview(pdf, 0, decode))
        image.writeText("old")
        assertTrue(image.setLastModified(pdf.lastModified() - 1000))
        assertNull(loadPdfPagePreview(pdf, 0, decode))
        assertTrue(image.setLastModified(pdf.lastModified() + 1000))
        assertNull(loadPdfPagePreview(pdf, 0, decode))
        image.delete()
        assertNull(loadPdfPagePreview(pdf, 0, decode))
    }

    @Test fun replacedSourceAndMalformedOrMissingMarkersRejectOldJxl() {
        val (pdf, _) = cache()
        val original = pdf.readBytes()
        pdf.appendText("replacement")
        assertNull(loadPdfPagePreview(pdf, 0) { "stale" })
        pdf.writeBytes(original)
        assertTrue(pdf.setLastModified(1_700_000_000_124L))
        assertNull(pdfPagePreviewCount(pdf))
        assertTrue(pdf.setLastModified(1_700_000_000_123L))
        val marker = File(pdf.parentFile, ".metadata/.fullimages-state/${pdf.nameWithoutExtension}.preview.json")
        marker.writeText("partial sync")
        assertNull(loadPdfPagePreview(pdf, 0) { "invalid" })
        marker.delete()
        assertNull(loadPdfPagePreview(pdf, 0) { "uncommitted" })
    }

    @Test fun decodeErrorsAndSourceChangesDuringDecodeUsePdfFallback() {
        val (pdf, _) = cache()
        assertNull(loadPdfPagePreview<String>(pdf, 0) { throw IllegalArgumentException("bad JXL") })
        assertNull(loadPdfPagePreview<String>(pdf, 0) { throw UnsatisfiedLinkError("codec missing") })
        assertNull(loadPdfPagePreview(pdf, 0) { pdf.appendText("changed during decode"); "stale" })
    }

    @Test fun unrecognizedPolicyOrWrongSourceIdentityDoesNotDecode() {
        val (pdf, _) = cache()
        val marker = File(pdf.parentFile, ".metadata/.fullimages-state/${pdf.nameWithoutExtension}.preview.json")
        val original = marker.readText()
        for (invalid in listOf(
            original.replace("\"policyVersion\":2", "\"policyVersion\":3"),
            original.replace(pdf.name, "another.pdf"),
            original.replace("\"pageCount\":2", "\"pageCount\":0")
        )) {
            marker.writeText(invalid)
            assertNull(loadPdfPagePreview(pdf, 0) { error("Invalid marker must not decode") })
        }
    }

    @Test fun imageOrMarkerChangesDuringDecodeRejectTheResult() {
        val (pdf, image) = cache()
        assertNull(loadPdfPagePreview(pdf, 0) { image.appendText("partial replacement"); "stale" })
        assertTrue(image.setLastModified(pdf.lastModified()))
        assertNull(loadPdfPagePreview(pdf, 0) {
            File(pdf.parentFile, ".metadata/.fullimages-state/${pdf.nameWithoutExtension}.preview.json").delete()
            "stale"
        })
    }
}
