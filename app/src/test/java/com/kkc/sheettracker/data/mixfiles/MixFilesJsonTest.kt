package com.kkc.sheettracker.data.mixfiles

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MixFilesJsonTest {
    @Test
    fun parsesCatalogRowsEntriesAndFrzRemaining() {
        val catalog = requireNotNull(MixFilesJson.parseCatalog(SAMPLE))
        assertEquals("711 - WIECHERT 88141 WYMORE", catalog.job)
        assertEquals("19mm Pre_Finished", catalog.material)
        assertEquals("folder-sha", catalog.folderRevision)
        val row = catalog.files.single()
        assertEquals(MixFileKind.FRZ, row.kind)
        assertEquals("19mm Pre_FinishedMix.mix", row.companion)
        assertEquals("file-sha", row.revision)
        assertFalse(row.companionMismatch)
        val entry = row.entries.single()
        assertEquals("C:\\AAAAAAAAAAAAAAAA\\711 - WIECHERT 88141 WYMORE\\19mm Pre_Finished\\R870107N.pgm", entry.path)
        assertEquals("R870107N.pgm", entry.pgm)
        assertEquals(1, entry.quantity)
        assertEquals(1, entry.remaining)
        assertEquals("16973824", entry.parameters["T"])
        assertEquals(listOf("DX", "DY", "DZ", "R", "BX", "BY", "BZ", "T", "V"), entry.parameters.keys.toList())
        assertEquals("AD", entry.fields)
        assertTrue(entry.pgmExists)
        assertFalse(entry.headerDrift)
    }

    @Test
    fun parsesUndecodableFileWithErrorAndNoEntries() {
        val catalog = requireNotNull(MixFilesJson.parseCatalog("""{"ok":true,"job":"J","material":"M","folderRevision":"f","files":[{"file":"broken.mix","kind":"mix","revision":"r","companionMismatch":false,"error":"decode failed","entries":[]}]}"""))
        assertEquals("decode failed", catalog.files.single().error)
        assertTrue(catalog.files.single().entries.isEmpty())
    }

    @Test
    fun toleratesMissingOptionalFields() {
        val catalog = requireNotNull(MixFilesJson.parseCatalog(SAMPLE))
        val row = catalog.files.single()
        assertNull(row.modifiedAt)
        assertNull(row.revisionNumber)
        assertNull(row.revisionOf)
    }

    @Test
    fun serializesEntrySpecs() {
        assertEquals("""{"pgm":"R1.pgm"}""", MixFilesJson.entry(EntrySpec.NewPgm("R1.pgm")).toString())
        val path = "Z:\\OLD JOB\\R1.pgm"
        val existing = MixFilesJson.entry(EntrySpec.Existing(path, linkedMapOf("T" to "0016973824", "custom" to " 1E+3 "), "AD"))
        assertEquals(setOf("path", "parameters", "fields"), existing.keySet())
        assertEquals(path, existing["path"].asString)
        assertEquals("0016973824", existing.getAsJsonObject("parameters")["T"].asString)
        assertEquals(" 1E+3 ", existing.getAsJsonObject("parameters")["custom"].asString)
        assertEquals(listOf("T", "custom"), existing.getAsJsonObject("parameters").keySet().toList())
        assertEquals("AD", existing["fields"].asString)
        assertEquals("""{"pgm":"R1.pgm"}""", MixFilesJson.entry(EntrySpec.Pending("R1.pgm", null, null)).toString())
        assertEquals(JsonParser.parseString("""{"pgm":"R1.pgm","quantity":4,"remaining":2}"""), MixFilesJson.entry(EntrySpec.Pending("R1.pgm", 4, 2)))
        assertFalse(MixFilesJson.entry(EntrySpec.Existing(path, emptyMap(), null)).has("fields"))
    }

    @Test
    fun parsesRevisionRowsAndOptionalEntryFields() {
        val document = JsonParser.parseString(SAMPLE).asJsonObject
        val row = document.getAsJsonArray("files")[0].asJsonObject
        row.addProperty("kind", "revision")
        row.addProperty("revisionOf", "Original.frz")
        row.addProperty("revisionNumber", 2)
        row.addProperty("modifiedAt", "2026-10-30T10:00:00")
        row.getAsJsonArray("entries")[0].asJsonObject.remove("remaining")
        row.getAsJsonArray("entries")[0].asJsonObject.remove("fields")
        val parsed = requireNotNull(MixFilesJson.parseCatalog(document.toString())).files.single()
        assertEquals(MixFileKind.REVISION, parsed.kind)
        assertEquals("Original.frz", parsed.revisionOf)
        assertEquals(2, parsed.revisionNumber)
        assertEquals("2026-10-30T10:00:00", parsed.modifiedAt)
        assertNull(parsed.entries.single().remaining)
        assertNull(parsed.entries.single().fields)
    }

    @Test
    fun serializesOptionalExistingCountsForPairSplitWithoutChangingPlainShape() {
        val plain = EntrySpec.Existing("C:\\Job\\A.pgm", linkedMapOf("V" to "010.0"), "AD")
        assertEquals(setOf("path", "parameters", "fields"), MixFilesJson.entry(plain).keySet())
        val counted = MixFilesJson.entry(plain.copy(quantity = 3, remaining = 2))
        assertEquals(setOf("path", "parameters", "fields", "quantity", "remaining"), counted.keySet())
        assertEquals(3, counted["quantity"].asInt)
        assertEquals(2, counted["remaining"].asInt)
        assertEquals("010.0", counted.getAsJsonObject("parameters")["V"].asString)
    }

    @Test
    fun rejectsMalformedOrMistypedCatalogs() {
        listOf("not json", "[]", "{}", SAMPLE.replace("\"ok\":true", "\"ok\":false"),
            SAMPLE.replace("\"quantity\":1", "\"quantity\":\"1\""),
            SAMPLE.replace("\"T\":\"16973824\"", "\"T\":16973824"),
            SAMPLE.replace("\"kind\":\"frz\"", "\"kind\":\"unknown\""),
            SAMPLE.replace("\"pgmExists\":true", "\"pgmExists\":\"true\""),
            SAMPLE.replace("\"remaining\":1", "\"remaining\":1.5")
        ).forEach { assertNull(it, MixFilesJson.parseCatalog(it)) }
    }

    companion object {
        // Catalog and FRZ row from the service guide, with an unknown future field.
        const val SAMPLE = """{"ok":true,"job":"711 - WIECHERT 88141 WYMORE","material":"19mm Pre_Finished","folderRevision":"folder-sha","files":[{"file":"19MM PRE_FINISHEDMIX.FRZ","kind":"frz","revisionOf":null,"revision":"file-sha","companion":"19mm Pre_FinishedMix.mix","companionMismatch":false,"error":null,"future":"ignored","entries":[{"path":"C:\\AAAAAAAAAAAAAAAA\\711 - WIECHERT 88141 WYMORE\\19mm Pre_Finished\\R870107N.pgm","pgm":"R870107N.pgm","parameters":{"DX":"96.5","DY":"48.5","DZ":"0.746","R":"1","BX":"0","BY":"0","BZ":"2","T":"16973824","V":"10"},"fields":"AD","quantity":1,"remaining":1,"pgmExists":true,"headerDrift":false}]}]}"""
    }
}
