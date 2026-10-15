package com.kkc.sheettracker.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PdfThumbBundleStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun pdf(parent: File = temporary.root): File {
        parent.mkdirs()
        return File(parent, "592 - Sheets [A].PDF").apply {
            writeText("PDF contents are never opened by the thumbnail reader")
            check(setLastModified(1_700_000_000_123L))
        }
    }

    private fun writeBundle(
        pdf: File,
        pages: Int = 3,
        entries: List<Int> = (1..pages).toList(),
        version: Int = 1,
        width: Int = 200,
        sourceFile: String = pdf.name,
        size: Long = pdf.length(),
        mtimeNs: Long = pdf.lastModified() * 1_000_000 + 654_321
    ): File {
        val file = File(pdf.parentFile, ".metadata/.fullimages-state/${pdf.nameWithoutExtension}.thumbs.zip")
        file.parentFile!!.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            for (number in entries) {
                zip.putNextEntry(ZipEntry("p${number.toString().padStart(3, '0')}.webp"))
                zip.write("page-$number".toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                """{"version":$version,"sourceFile":"$sourceFile","source":{"size":$size,"mtimeNs":$mtimeNs},"pageCount":$pages,"width":$width}"""
                    .toByteArray()
            )
            zip.closeEntry()
        }
        return file
    }

    private val text: (ByteArray) -> String? = { it.toString(Charsets.UTF_8) }

    @Test fun validBundleDecodesEveryPageByZeroBasedIndex() {
        val source = pdf()
        writeBundle(source)
        assertEquals(mapOf(0 to "page-1", 1 to "page-2", 2 to "page-3"), loadPdfThumbBundle(source, text))
    }

    @Test fun darkAndLightSourcesUseTheirOwnParentBundles() {
        val light = pdf()
        val dark = pdf(File(temporary.root, "DARK MODE"))
        writeBundle(light, pages = 1)
        assertNotNull(loadPdfThumbBundle(light, text))
        assertNull(loadPdfThumbBundle(dark, text))
    }

    @Test fun missingBundleOrMissingPdfIsNull() {
        val source = pdf()
        assertNull(loadPdfThumbBundle(source, text))
        writeBundle(source)
        source.delete()
        assertNull(loadPdfThumbBundle(source, text))
    }

    @Test fun staleOrMismatchedManifestIsRejected() {
        val source = pdf()
        for (bad in listOf<() -> Unit>(
            { writeBundle(source, version = 2) },
            { writeBundle(source, width = 340) },
            { writeBundle(source, sourceFile = "another.pdf") },
            { writeBundle(source, size = source.length() + 1) },
            { writeBundle(source, mtimeNs = (source.lastModified() + 1000) * 1_000_000) },
            { writeBundle(source, pages = 0) }
        )) {
            bad()
            assertNull(loadPdfThumbBundle(source, text))
        }
    }

    @Test fun replacedPdfRejectsTheOldBundle() {
        val source = pdf()
        writeBundle(source)
        source.appendText("replacement")
        assertNull(loadPdfThumbBundle(source, text))
    }

    @Test fun missingEntriesAndUndecodablePagesAreSkippedNotFatal() {
        val source = pdf()
        writeBundle(source, pages = 3, entries = listOf(1, 3))
        assertEquals(mapOf(0 to "page-1", 2 to "page-3"), loadPdfThumbBundle(source, text))
        val partial = loadPdfThumbBundle(source) { bytes ->
            if (bytes.toString(Charsets.UTF_8) == "page-3") error("bad webp") else "ok"
        }
        assertEquals(mapOf(0 to "ok"), partial)
    }

    @Test fun corruptZipIsNull() {
        val source = pdf()
        val bundle = writeBundle(source)
        bundle.writeBytes(bundle.readBytes().copyOf(30))
        assertNull(loadPdfThumbBundle(source, text))
    }

    @Test fun pdfReplacedDuringDecodeRejectsTheResult() {
        val source = pdf()
        writeBundle(source)
        assertNull(loadPdfThumbBundle(source) { source.appendText("changed during decode"); "stale" })
    }
}
