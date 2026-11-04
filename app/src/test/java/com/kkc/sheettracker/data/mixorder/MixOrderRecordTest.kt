package com.kkc.sheettracker.data.mixorder

import com.kkc.sheettracker.data.mixfiles.DraftEntry
import com.kkc.sheettracker.data.mixfiles.MixDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixOrderRecordTest {
    private fun sheet(head: Int, vararg pgms: String, smallEdge: Int = 0) = SmartSheet(
        headPage = head,
        pgms = pgms.toList(),
        features = SheetFeatures(smallEdge, 1, 0.25, 2.5, 8)
    )

    private val sheets = listOf(sheet(1, "A1.pgm", smallEdge = 0), sheet(2, "B2.pgm", "B2z.pgm", smallEdge = 3), sheet(3, "C3.pgm"))

    private fun context(mode: MixOrderMode = MixOrderMode.SMART) = SmartMixContext(
        mode = mode,
        sheets = sheets,
        defaultOrder = sheets.map { it.key },
        suggestedOrder = listOf(sheets[1].key, sheets[2].key, sheets[0].key),
        weights = PRIOR_WEIGHTS
    )

    private fun draft(vararg pgms: String, newFile: String? = "NewMix.mix") = MixDraft(
        groupKey = "newmix",
        baseMixRevision = null,
        baseFrzRevision = null,
        entries = pgms.mapIndexed { i, pgm -> DraftEntry(i + 1L, null, pgm, 1, null, null) },
        newFile = newFile,
        splitOff = emptyList(),
        changeCount = 1,
        baselineEntries = emptyList()
    )

    private fun build(
        submitted: MixDraft = draft("B2.pgm", "B2z.pgm", "C3.pgm", "A1.pgm"),
        context: SmartMixContext? = context(),
        saved: Boolean = true,
        locked: Collection<String> = emptyList()
    ) = buildMixOrderRecord(
        submitted = submitted,
        context = context,
        saved = saved,
        lockedPgms = locked,
        job = "530",
        material = "Maple",
        tabletId = "tab-1",
        appVersion = "9.0.17",
        now = 1_793_000_000_000L,
        recordId = "rec-1"
    )

    @Test
    fun nullForExistingMix() {
        assertNull(build(submitted = draft("A1.pgm", newFile = null)))
    }

    @Test
    fun nullWhenNotSaved() {
        assertNull(build(saved = false))
    }

    @Test
    fun nullWithoutContext() {
        assertNull(build(context = null))
    }

    @Test
    fun finalOrderFollowsEntries() {
        val record = build()!!
        assertEquals(listOf(sheets[1].key, sheets[2].key, sheets[0].key), record.finalOrder)
        assertEquals(sheets.map { it.key }, record.defaultOrder)
        assertEquals(context().suggestedOrder, record.suggestedOrder)
        assertEquals("rec-1", record.recordId)
        assertEquals("530", record.job)
        assertEquals("Maple", record.material)
        assertEquals("NewMix.mix", record.mixFile)
        assertEquals("tab-1", record.tabletId)
        assertEquals("9.0.17", record.appVersion)
        assertEquals("smart", record.mode)
        assertEquals(PRIOR_WEIGHTS.values, record.weightsUsed)
        assertEquals(EDGE_BAND_IN, record.edgeBandIn, 0.0)
        assertEquals(SMALL_PART_IN, record.smallPartIn, 0.0)
        assertEquals(sheets.associate { it.key to it.features }, record.sheets)
        assertTrue(record.appliedAt.startsWith("20"))
    }

    @Test
    fun finalOrderUsesFirstPgmOfEachSheetCaseInsensitively() {
        val record = build(submitted = draft("c3.PGM", "a1.pgm", "B2Z.pgm", "b2.pgm"))!!
        assertEquals(listOf(sheets[2].key, sheets[0].key, sheets[1].key), record.finalOrder)
    }

    @Test
    fun defaultModeIsRecorded() {
        assertEquals("default", build(context = context(MixOrderMode.DEFAULT))!!.mode)
    }

    @Test
    fun lockedSheetsFromPgms() {
        val record = build(locked = listOf("b2z.pgm", "unknown.pgm"))!!
        assertEquals(listOf(sheets[1].key), record.lockedSheets)
        assertTrue(build()!!.lockedSheets.isEmpty())
    }

    @Test
    fun jsonRoundTrip() {
        val record = build()!!
        val json = record.toJson()
        assertTrue(json, json.contains("\"mode\": \"smart\""))
        assertEquals("\"smart\"", MixOrderJson.gson.toJson(MixOrderMode.SMART))
        assertEquals("\"default\"", MixOrderJson.gson.toJson(MixOrderMode.DEFAULT))
        val parsed = parseMixOrderRecord(json)
        assertNotNull(parsed)
        assertEquals(record, parsed)
        assertEquals(sheets[1].features, parsed!!.sheets.getValue(sheets[1].key))
    }
}
