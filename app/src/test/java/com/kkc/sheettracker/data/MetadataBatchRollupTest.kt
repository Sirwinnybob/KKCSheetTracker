package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodJob
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MetadataBatchRollupTest {
    private val folder = "686 - Example"
    private val tabletId = "tablet-test"

    private fun batchDoc(batchId: String, name: String, vararg rows: HardwoodCutlistRow) = HardwoodDocumentIndex(
        docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
        sourceKind = HardwoodDocuments.SOURCE_KIND_CV_METADATA,
        batchId = batchId,
        batchName = name,
        rows = rows.toList()
    )

    private fun twoBatchIndex() = HardwoodCutlistIndex(
        documents = listOf(
            batchDoc("b1", "Main", HardwoodCutlistRow(rowId = "cv:b1:a", qty = 2)),
            batchDoc("b2", "Second", HardwoodCutlistRow(rowId = "cv:b2:a", qty = 2))
        )
    )

    private fun newStore(index: HardwoodCutlistIndex, transformJson: (String) -> String = { it }): Pair<HardwoodsProgressStore, HardwoodJob> {
        val baseDir = Files.createTempDirectory("cv-rollup-test").toFile()
        val path = File(baseDir, "$folder/.metadata/hardwoods/cutlist_index.json")
        path.parentFile.mkdirs()
        path.writeText(transformJson(Gson().toJson(index)))
        return HardwoodsProgressStore(baseDir, tabletId) to
            HardwoodJob(folderName = folder, jobNumber = "686", jobName = "Example", index = index)
    }

    @Test
    fun completingARowInOneBatchDoesNotTouchTheOther() {
        val (store, job) = newStore(twoBatchIndex())
        store.incrementDoneCount(jobFolderName = folder, docType = "FACE_FRAME_CUT_LIST", rowId = "cv:b1:a", qty = 2)

        val summary = store.summarizeJob(job)
        assertEquals(listOf(1, 0), summary.documents.map { it.counts.donePieces })
        assertEquals(4, summary.counts.totalPieces)
        assertEquals(1, summary.counts.donePieces)
    }

    @Test
    fun perTypeSummaryMergeEqualsTheJobTotals() {
        val (store, job) = newStore(twoBatchIndex())
        store.incrementDoneCount(jobFolderName = folder, docType = "FACE_FRAME_CUT_LIST", rowId = "cv:b2:a", qty = 2)

        val summary = store.summarizeJob(job)
        val merged = HardwoodDocuments.mergeSummaries(summary.documents).getValue(HardwoodDocType.FACE_FRAME_CUT_LIST)
        assertEquals(summary.counts, merged.counts)
        assertEquals(2, merged.rowCount)
    }

    @Test
    fun closetRodRowsComeFromEveryBatch() {
        val index = HardwoodCutlistIndex(
            documents = listOf(
                HardwoodDocumentIndex(docType = HardwoodDocType.CLOSET_ROD_CUT_LIST, batchId = "b1", rows = listOf(HardwoodCutlistRow(rowId = "cv:b1:r", qty = 1))),
                HardwoodDocumentIndex(docType = HardwoodDocType.CLOSET_ROD_CUT_LIST, batchId = "b2", rows = listOf(HardwoodCutlistRow(rowId = "cv:b2:r", qty = 2)))
            )
        )
        assertEquals(
            listOf("cv:b1:r", "cv:b2:r"),
            HardwoodDocuments.rowsOfTypeOrNull(index, HardwoodDocType.CLOSET_ROD_CUT_LIST)?.map { it.rowId }
        )
    }

    @Test
    fun unknownDocTypeInIndexFileDoesNotCrashAndKeepsValidDocCounts() {
        val index = HardwoodCutlistIndex(
            documents = twoBatchIndex().documents + HardwoodDocumentIndex(
                docType = HardwoodDocType.CLOSET_ROD_CUT_LIST,
                rows = listOf(HardwoodCutlistRow(rowId = "future:a", qty = 1))
            )
        )
        // A newer publisher wrote a doc type this build does not know.
        val (store, job) = newStore(index) { it.replace("\"CLOSET_ROD_CUT_LIST\"", "\"FUTURE_DOC_TYPE\"") }
        val knownJob = job.copy(index = HardwoodDocuments.dropUnknownDocTypes(index.copy(documents = twoBatchIndex().documents)))

        store.incrementDoneCount(jobFolderName = folder, docType = "FACE_FRAME_CUT_LIST", rowId = "cv:b1:a", qty = 2)

        val progress = store.getRowProgressMap(folder)
        assertEquals(1, progress.getValue("FACE_FRAME_CUT_LIST" to "cv:b1:a").doneCount)
        val summary = store.summarizeJob(knownJob)
        assertEquals(4, summary.counts.totalPieces)
        assertEquals(1, summary.counts.donePieces)
    }
}
