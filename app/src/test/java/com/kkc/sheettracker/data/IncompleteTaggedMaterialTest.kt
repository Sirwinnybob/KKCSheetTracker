package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialMetadata
import com.kkc.sheettracker.data.models.MaterialUiModel
import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.SheetStatusSnapshot
import com.kkc.sheettracker.data.models.StatusCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncompleteTaggedMaterialTest {

    private val job = Job(
        folderName = "530-Smith",
        jobNumber = "530",
        jobName = "Smith Kitchen"
    )

    private fun material(label: String?, pageCount: Int = 3) = Material(
        pdfFilename = "cabinets.pdf",
        materialName = "Maple Ply",
        pageCount = pageCount,
        fileFingerprint = "abc123",
        metadata = MaterialMetadata(
            remakeLabel = label,
            pages = (1..pageCount).map { PageMetadata(pageNumber = it) }
        )
    )

    private fun uiModel(complete: Int, total: Int, reNested: Int = 0) = MaterialUiModel(
        pdfFilename = "cabinets.pdf",
        materialName = "Maple Ply",
        counts = StatusCounts(
            total = total,
            complete = complete,
            reNested = reNested,
            notStarted = (total - complete - reNested).coerceAtLeast(0)
        ),
        completionFraction = if (total > 0) complete.toFloat() / total.toFloat() else 0f
    )

    @Test
    fun labeledIncompleteMaterialProducesItemWithCorrectFieldMapping() {
        val item = buildIncompleteTaggedMaterialItem(
            label = "Remake",
            job = job,
            material = material(label = "Remake"),
            materialUiModel = uiModel(complete = 1, total = 3),
            pageStatusByNumber = emptyMap()
        )

        assertEquals(job.folderName, item?.jobFolderName)
        assertEquals(job.jobNumber, item?.jobNumber)
        assertEquals("Maple Ply", item?.materialName)
        assertEquals("cabinets.pdf", item?.pdfFilename)
        assertEquals("abc123", item?.fileFingerprint)
        // No page has a status recorded, so the first trackable page (1) is next incomplete.
        assertEquals(1, item?.nextIncompletePage)
        assertEquals(1, item?.lastTouchedPage)
        assertEquals(0L, item?.lastTouchedAtMs)
    }

    @Test
    fun nullLabelProducesNoItem() {
        val item = buildIncompleteTaggedMaterialItem(
            label = null,
            job = job,
            material = material(label = null),
            materialUiModel = uiModel(complete = 1, total = 3),
            pageStatusByNumber = emptyMap()
        )

        assertNull(item)
    }

    @Test
    fun fullyCompleteMaterialProducesNoItemEvenWhenLabeled() {
        val item = buildIncompleteTaggedMaterialItem(
            label = "Misc",
            job = job,
            material = material(label = "Misc"),
            materialUiModel = uiModel(complete = 3, total = 3),
            pageStatusByNumber = emptyMap()
        )

        assertNull(item)
    }

    @Test
    fun completePlusReNestedAtOrAboveTotalProducesNoItem() {
        val item = buildIncompleteTaggedMaterialItem(
            label = "Remake",
            job = job,
            material = material(label = "Remake"),
            materialUiModel = uiModel(complete = 1, total = 3, reNested = 2),
            pageStatusByNumber = emptyMap()
        )

        assertNull(item)
    }

    @Test
    fun nextIncompletePageSkipsAlreadyCompletedPages() {
        val mat = material(label = "Remake")
        val pageStatuses = mapOf(
            1 to SheetStatusSnapshot(status = com.kkc.sheettracker.data.models.SheetStatus.COMPLETE),
            2 to SheetStatusSnapshot(status = com.kkc.sheettracker.data.models.SheetStatus.NOT_STARTED)
        )

        val item = buildIncompleteTaggedMaterialItem(
            label = "Remake",
            job = job,
            material = mat,
            materialUiModel = uiModel(complete = 1, total = 3),
            pageStatusByNumber = pageStatuses
        )

        assertEquals(2, item?.nextIncompletePage)
    }
}
