package com.kkc.sheettracker.data.unified

import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CvCutlistIndexDecodeTest {
    private val folder = "686 - Example"

    private fun documentsFor(indexJson: String): List<HardwoodDocumentIndex> {
        val baseDir = Files.createTempDirectory("cv-cutlist-decode-test").toFile()
        val metadata = File(baseDir, "$folder/.metadata").apply { mkdirs() }
        File(metadata, "deployment_gate.json").writeText("""{"deployed": true}""")
        File(metadata, "hardwoods").mkdirs()
        File(metadata, "hardwoods/cutlist_index.json").writeText(indexJson)
        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)
        val snapshot = engine.getHardwoodsSnapshot(folder)
        assertNotNull("engine produced no hardwoods snapshot for the seeded job", snapshot)
        return snapshot!!.job.index?.documents.orEmpty()
    }

    @Test
    fun legacyIndexDecodesWithEmptyBatchFields() {
        val docs = documentsFor(
            """{"generatedAt":"t","documents":[{"docType":"FACE_FRAME_CUT_LIST","pdfFilename":"f.pdf","pageCount":2,
               "rows":[{"rowId":"r1","page":1,"rowOrdinal":0,"qty":2,"description":"Rail","width":"2","length":"30","cabinets":["1"]}],"totals":[]}]}"""
        )
        val doc = docs.single()
        assertEquals("f.pdf", doc.pdfFilename)
        assertEquals("", doc.sourceKind)
        assertEquals("", doc.batchId)
        assertTrue(doc.doors.isEmpty())
    }

    @Test
    fun metadataDocKeepsBatchFieldsDoorsAndIgnoresFutureKeys() {
        val docs = documentsFor(
            """{"documents":[{"docType":"DOOR_CUT_LIST","sourceKind":"CV_METADATA","batchId":"b1","batchName":"Main","revisionId":"r1",
               "futureDocKey":{"x":1},
               "rows":[{"rowId":"cv:b1:a","page":0,"rowOrdinal":0,"qty":3,"description":"Stile","width":"2","length":"30","cabinets":["1","2"],
                        "components":[{"sourceId":"cvpart:1","cabinet":"1","room":"Kitchen","quantity":3}],"futureRowKey":true}],
               "doors":[{"id":"d1","cabinet":"1","room":"Kitchen","style":"2 1/4\" Flat Panel","width":"15","height":"30","profiles":["a","b"],"finish":"paint"}],
               "totals":[]}],"futureRoot":[1,2]}"""
        )
        val doc = docs.single()
        assertEquals(HardwoodDocType.DOOR_CUT_LIST, doc.docType)
        assertEquals("CV_METADATA", doc.sourceKind)
        assertEquals("b1", doc.batchId)
        assertEquals("Main", doc.batchName)
        assertEquals("r1", doc.revisionId)
        assertEquals("", doc.pdfFilename)
        assertEquals(listOf("1", "2"), doc.rows.single().cabinets)
        val door = doc.doors.single()
        assertEquals("2 1/4\" Flat Panel", door.style)
        assertEquals(listOf("a", "b"), door.profiles)
    }

    @Test
    fun unknownDocTypeIsDroppedNotMislabelled() {
        val docs = documentsFor(
            """{"documents":[{"docType":"HINGE_ORDER_LIST","rows":[{"rowId":"h1","qty":4}]},
                              {"docType":"NAILER_CUT_LIST","pdfFilename":"n.pdf","rows":[{"rowId":"n1","qty":1}]}]}"""
        )
        assertEquals(listOf(HardwoodDocType.NAILER_CUT_LIST), docs.map { it.docType })
    }
}
