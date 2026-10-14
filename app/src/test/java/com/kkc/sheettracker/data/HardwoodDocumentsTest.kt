package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.CacheIndexHardwoodsDocType
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocSummary
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwoodDocumentsTest {
    private fun row(id: String, qty: Int = 1) = HardwoodCutlistRow(rowId = id, qty = qty)

    private fun legacy(type: HardwoodDocType, pdf: String = "x.pdf", vararg rows: HardwoodCutlistRow) =
        HardwoodDocumentIndex(docType = type, pdfFilename = pdf, rows = rows.toList())

    private fun batch(type: HardwoodDocType, batchId: String, name: String, vararg rows: HardwoodCutlistRow) =
        HardwoodDocumentIndex(
            docType = type,
            sourceKind = HardwoodDocuments.SOURCE_KIND_CV_METADATA,
            batchId = batchId,
            batchName = name,
            rows = rows.toList()
        )

    @Test
    fun metadataBackedDocIsAvailableWithoutAnyPdf() {
        val doc = batch(HardwoodDocType.FACE_FRAME_CUT_LIST, "b1", "Main")
        assertTrue(HardwoodDocuments.isAvailable(doc) { false })
    }

    @Test
    fun legacyDocNeedsItsPdfToExist() {
        val doc = legacy(HardwoodDocType.NAILER_CUT_LIST, "nailer.pdf")
        assertTrue(HardwoodDocuments.isAvailable(doc) { it == "nailer.pdf" })
        assertFalse(HardwoodDocuments.isAvailable(doc) { false })
        assertFalse(HardwoodDocuments.isAvailable(legacy(HardwoodDocType.NAILER_CUT_LIST, ""), { true }))
    }

    @Test
    fun retiredDoorListIsNeverAvailable() {
        assertFalse(HardwoodDocuments.isAvailable(batch(HardwoodDocType.DOOR_LIST, "b1", "Main")) { true })
        assertFalse(HardwoodDocuments.isAvailable(legacy(HardwoodDocType.DOOR_LIST)) { true })
    }

    @Test
    fun unknownDocTypeIsDroppedWhileKnownDocsSurvive() {
        val json = """
            {"documents":[
              {"docType":"HINGE_ORDER_LIST","rows":[{"rowId":"h1","qty":4}]},
              {"docType":"FACE_FRAME_CUT_LIST","rows":[{"rowId":"f1","qty":2}],"futureKey":{"a":1}}
            ],"futureRoot":true}
        """.trimIndent()
        val decoded = Gson().fromJson(json, HardwoodCutlistIndex::class.java)
        val cleaned = HardwoodDocuments.dropUnknownDocTypes(decoded)
        assertEquals(listOf(HardwoodDocType.FACE_FRAME_CUT_LIST), cleaned?.documents?.map { it.docType })
    }

    @Test
    fun dropUnknownDocTypesHandlesNullAndReturnsSameInstanceWhenNothingDropped() {
        assertNull(HardwoodDocuments.dropUnknownDocTypes(null))
        val index = HardwoodCutlistIndex(documents = listOf(legacy(HardwoodDocType.NAILER_CUT_LIST)))
        assertSame(index, HardwoodDocuments.dropUnknownDocTypes(index))
    }

    @Test
    fun rowsOfTypeConcatenatesBatchesAndIsNullWhenTypeAbsent() {
        val index = HardwoodCutlistIndex(
            documents = listOf(
                batch(HardwoodDocType.CLOSET_ROD_CUT_LIST, "b1", "A", row("cv:b1:a")),
                batch(HardwoodDocType.CLOSET_ROD_CUT_LIST, "b2", "B", row("cv:b2:a"), row("cv:b2:b"))
            )
        )
        assertEquals(
            listOf("cv:b1:a", "cv:b2:a", "cv:b2:b"),
            HardwoodDocuments.rowsOfTypeOrNull(index, HardwoodDocType.CLOSET_ROD_CUT_LIST)?.map { it.rowId }
        )
        assertNull(HardwoodDocuments.rowsOfTypeOrNull(index, HardwoodDocType.DOOR_CUT_LIST))
        assertEquals(
            emptyList<String>(),
            HardwoodDocuments.rowsOfTypeOrNull(
                HardwoodCutlistIndex(documents = listOf(legacy(HardwoodDocType.DOOR_CUT_LIST))),
                HardwoodDocType.DOOR_CUT_LIST
            )?.map { it.rowId }
        )
    }

    @Test
    fun mergedByTypePassesSingleDocsThroughAndMergesBatches() {
        val single = legacy(HardwoodDocType.NAILER_CUT_LIST, "n.pdf", row("n1"))
        val a = batch(HardwoodDocType.FACE_FRAME_CUT_LIST, "b1", "A", row("cv:b1:a"))
        val b = batch(HardwoodDocType.FACE_FRAME_CUT_LIST, "b2", "B", row("cv:b2:a"))
        val merged = HardwoodDocuments.mergedByType(listOf(single, a, b))

        assertSame(single, merged[HardwoodDocType.NAILER_CUT_LIST])
        val face = merged.getValue(HardwoodDocType.FACE_FRAME_CUT_LIST)
        assertEquals(listOf("cv:b1:a", "cv:b2:a"), face.rows.map { it.rowId })
        assertEquals("", face.batchId)
    }

    @Test
    fun mergeSummariesSumsCountsPerType() {
        val merged = HardwoodDocuments.mergeSummaries(
            listOf(
                HardwoodDocSummary(HardwoodDocType.FACE_FRAME_CUT_LIST, rowCount = 2, counts = HardwoodStatusCounts(4, 1, 0, 0)),
                HardwoodDocSummary(HardwoodDocType.FACE_FRAME_CUT_LIST, rowCount = 3, counts = HardwoodStatusCounts(6, 2, 1, 0)),
                HardwoodDocSummary(HardwoodDocType.NAILER_CUT_LIST, rowCount = 1, counts = HardwoodStatusCounts(1, 0, 0, 0))
            )
        )
        val face = merged.getValue(HardwoodDocType.FACE_FRAME_CUT_LIST)
        assertEquals(5, face.rowCount)
        assertEquals(HardwoodStatusCounts(10, 3, 1, 0), face.counts)
        assertEquals(1, merged.getValue(HardwoodDocType.NAILER_CUT_LIST).rowCount)
    }

    @Test
    fun selectDocPrefersBatchThenOwningRowThenFirst() {
        val a = batch(HardwoodDocType.FACE_FRAME_CUT_LIST, "b1", "A", row("cv:b1:a"))
        val b = batch(HardwoodDocType.FACE_FRAME_CUT_LIST, "b2", "B", row("cv:b2:a"))
        val docs = listOf(a, b)
        val type = HardwoodDocType.FACE_FRAME_CUT_LIST

        assertSame(b, HardwoodDocuments.selectDoc(docs, type, "b2", null))
        assertSame(b, HardwoodDocuments.selectDoc(docs, type, null, "cv:b2:a"))
        assertSame(a, HardwoodDocuments.selectDoc(docs, type, null, null))
        assertSame(a, HardwoodDocuments.selectDoc(docs, type, "missing", "also-missing"))
        assertNull(HardwoodDocuments.selectDoc(docs, HardwoodDocType.NAILER_CUT_LIST, "b1", null))
    }

    @Test
    fun cacheIndexDocTypeLabelUsesPlainTypeWhenBatchNameIsBlankNullOrMissing() {
        val gson = Gson()
        val missing = gson.fromJson("""{"docType":"FACE_FRAME_CUT_LIST"}""", CacheIndexHardwoodsDocType::class.java)
        val explicitNull = gson.fromJson("""{"docType":"FACE_FRAME_CUT_LIST","batchName":null}""", CacheIndexHardwoodsDocType::class.java)
        val blank = CacheIndexHardwoodsDocType(docType = "FACE_FRAME_CUT_LIST", batchName = "  ")
        assertEquals("FACE_FRAME_CUT_LIST", HardwoodDocuments.cacheIndexDocTypeLabel(missing))
        assertEquals("FACE_FRAME_CUT_LIST", HardwoodDocuments.cacheIndexDocTypeLabel(explicitNull))
        assertEquals("FACE_FRAME_CUT_LIST", HardwoodDocuments.cacheIndexDocTypeLabel(blank))
    }

    @Test
    fun cacheIndexDocTypeLabelAppendsBatchNameWhenNamed() {
        val named = CacheIndexHardwoodsDocType(docType = "FACE_FRAME_CUT_LIST", batchId = "b1", batchName = "Main")
        assertEquals("FACE_FRAME_CUT_LIST — Main", HardwoodDocuments.cacheIndexDocTypeLabel(named))
    }

    @Test
    fun cacheIndexDocTypeCountCountsDistinctTypesNotBatches() {
        val docTypes = listOf(
            CacheIndexHardwoodsDocType(docType = "FACE_FRAME_CUT_LIST", batchId = "b1", batchName = "A"),
            CacheIndexHardwoodsDocType(docType = "FACE_FRAME_CUT_LIST", batchId = "b2", batchName = "B"),
            CacheIndexHardwoodsDocType(docType = "NAILER_CUT_LIST")
        )
        assertEquals(2, HardwoodDocuments.cacheIndexDocTypeCount(docTypes))
        assertEquals(0, HardwoodDocuments.cacheIndexDocTypeCount(emptyList()))
    }

    @Test
    fun nullDocumentElementsAreToleratedByTheSharedRules() {
        val withNull = Gson().fromJson(
            """{"documents":[null,{"docType":"NAILER_CUT_LIST","pdfFilename":"n.pdf","rows":[{"rowId":"n1","qty":1}]}]}""",
            HardwoodCutlistIndex::class.java
        )
        val cleaned = HardwoodDocuments.dropUnknownDocTypes(withNull)
        assertEquals(listOf(HardwoodDocType.NAILER_CUT_LIST), cleaned?.documents?.map { it.docType })
        assertEquals(1, HardwoodDocuments.docsOfType(withNull, HardwoodDocType.NAILER_CUT_LIST).size)
        assertEquals(listOf("n1"), HardwoodDocuments.rowsOfTypeOrNull(withNull, HardwoodDocType.NAILER_CUT_LIST)?.map { it.rowId })
        val merged = HardwoodDocuments.mergedByType(withNull.documents)
        assertEquals(setOf(HardwoodDocType.NAILER_CUT_LIST), merged.keys)
    }

    @Test
    fun pendingRowIdsByTypeUnionsBatchesPerType() {
        val ff = HardwoodDocType.FACE_FRAME_CUT_LIST
        val docs = listOf(
            batch(ff, "b1", "A", row("cv:b1:a"), row("cv:b1:b")),
            batch(ff, "b2", "B", row("cv:b2:a")),
            legacy(HardwoodDocType.NAILER_CUT_LIST, "n.pdf", row("n1"))
        )
        val pending = HardwoodDocuments.pendingRowIdsByType(docs) { type, r ->
            (type == ff && r.rowId != "cv:b1:b") || r.rowId == "n1"
        }
        assertEquals(setOf("cv:b1:a", "cv:b2:a"), pending[ff])
        assertEquals(setOf("n1"), pending[HardwoodDocType.NAILER_CUT_LIST])
        val none = HardwoodDocuments.pendingRowIdsByType(docs) { _, _ -> false }
        assertEquals(emptySet<String>(), none[ff])
    }

    @Test
    fun firstDocWithPendingFindsTheBatchThatHasTheChanges() {
        val ff = HardwoodDocType.FACE_FRAME_CUT_LIST
        val a = batch(ff, "b1", "A", row("cv:b1:a"))
        val b = batch(ff, "b2", "B", row("cv:b2:a"))
        val docs = listOf(a, b)

        assertSame(b, HardwoodDocuments.firstDocWithPending(docs, ff, setOf("cv:b2:a")))
        assertSame(a, HardwoodDocuments.firstDocWithPending(listOf(a), ff, setOf("cv:b1:a")))
        assertNull(HardwoodDocuments.firstDocWithPending(docs, ff, emptySet()))
        assertNull(HardwoodDocuments.firstDocWithPending(docs, ff, setOf("other")))
        assertNull(HardwoodDocuments.firstDocWithPending(docs, HardwoodDocType.NAILER_CUT_LIST, setOf("cv:b1:a")))
    }

    @Test
    fun firstDocWithPendingPrefersTheCurrentDocWhenItHasPending() {
        val ff = HardwoodDocType.FACE_FRAME_CUT_LIST
        val a = batch(ff, "b1", "A", row("cv:b1:a"))
        val b = batch(ff, "b2", "B", row("cv:b2:a"))
        val both = setOf("cv:b1:a", "cv:b2:a")

        assertSame(b, HardwoodDocuments.firstDocWithPending(listOf(a, b), ff, both, preferred = b))
        // A preferred doc without pending rows loses to one that has them.
        assertSame(b, HardwoodDocuments.firstDocWithPending(listOf(a, b), ff, setOf("cv:b2:a"), preferred = a))
        // A preferred doc of another type is ignored.
        val nailer = legacy(HardwoodDocType.NAILER_CUT_LIST, "n.pdf", row("cv:b1:a"))
        assertSame(a, HardwoodDocuments.firstDocWithPending(listOf(a, b), ff, both, preferred = nailer))
    }
}
