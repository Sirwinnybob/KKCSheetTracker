package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HiddenMaterialEntry
import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsGlobalDoc
import com.kkc.sheettracker.data.models.HiddenMaterialsJobDoc
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwoodsJobDetailFilterTest {

    private fun job(rows: List<HardwoodCutlistRow>): HardwoodJob = HardwoodJob(
        folderName = "123 - Job",
        jobNumber = "123",
        jobName = "Job",
        index = HardwoodCutlistIndex(
            documents = listOf(
                HardwoodDocumentIndex(docType = HardwoodDocType.NAILER_CUT_LIST, rows = rows)
            )
        )
    )

    @Test
    fun `globally hidden material is excluded from the filtered job`() {
        val rows = listOf(
            HardwoodCutlistRow(rowId = "r1", material = "Maple"),
            HardwoodCutlistRow(rowId = "r2", material = "Oak")
        )
        val hiddenDocument = HiddenMaterialsDocument(
            global = HiddenMaterialsGlobalDoc(
                entries = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = "Maple"))
            )
        )

        val filtered = job(rows).filteredForHiddenMaterials(hiddenDocument)

        assertEquals(listOf("r2"), filtered.index!!.documents[0].rows.map { it.rowId })
    }

    @Test
    fun `job-scoped unhide overrides a global hide`() {
        val rows = listOf(HardwoodCutlistRow(rowId = "r1", material = "Maple"))
        val hiddenDocument = HiddenMaterialsDocument(
            global = HiddenMaterialsGlobalDoc(
                entries = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = "Maple"))
            ),
            jobs = mapOf(
                "123 - Job" to HiddenMaterialsJobDoc(
                    unhides = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = "Maple"))
                )
            )
        )

        val filtered = job(rows).filteredForHiddenMaterials(hiddenDocument)

        assertEquals(listOf("r1"), filtered.index!!.documents[0].rows.map { it.rowId })
    }

    @Test
    fun `no hidden entries leaves the job unchanged`() {
        val rows = listOf(HardwoodCutlistRow(rowId = "r1", material = "Maple"))

        val filtered = job(rows).filteredForHiddenMaterials(HiddenMaterialsDocument())

        assertEquals(rows, filtered.index!!.documents[0].rows)
    }

    @Test
    fun `null index is returned unchanged`() {
        val job = HardwoodJob(folderName = "123 - Job", jobNumber = "123", jobName = "Job", index = null)

        val filtered = job.filteredForHiddenMaterials(HiddenMaterialsDocument())

        assertEquals(job, filtered)
    }
}
