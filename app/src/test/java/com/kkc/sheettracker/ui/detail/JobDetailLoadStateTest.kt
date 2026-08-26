package com.kkc.sheettracker.ui.detail

import com.kkc.sheettracker.data.mixservice.MixCatalogEntry
import com.kkc.sheettracker.data.mixservice.MixCatalogSnapshot
import com.kkc.sheettracker.data.mixservice.MixLifecycle
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialMetadata
import com.kkc.sheettracker.data.models.PageMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class JobDetailLoadStateTest {
    @Test
    fun jobDetailLoadStateSeparatesPendingDataFromAnUnavailableJob() {
        assertEquals(JobDetailLoadState.LOADING, jobDetailLoadState(hasResolved = false, hasJob = false))
        assertEquals(JobDetailLoadState.AVAILABLE, jobDetailLoadState(hasResolved = true, hasJob = true))
        assertEquals(JobDetailLoadState.UNAVAILABLE, jobDetailLoadState(hasResolved = true, hasJob = false))
    }

    @Test
    fun `multiple active mixes create cards with only their mapped physical pages`() {
        val material = Material(
            pdfFilename = "19mm.pdf",
            materialName = "19mm",
            pageCount = 3,
            metadata = MaterialMetadata(
                pages = listOf(
                    PageMetadata(pageNumber = 1, sheetFiles = listOf("R1")),
                    PageMetadata(pageNumber = 2, sheetFiles = listOf("R2")),
                    PageMetadata(pageNumber = 3, sheetFiles = listOf("R3"))
                )
            )
        )
        val catalog = MixCatalogSnapshot(
            job = "648 - WIECHERT",
            material = "19mm",
            revision = 2,
            entries = listOf(
                MixCatalogEntry("Base", "Base.mix", MixLifecycle.ACTIVE, programs = listOf("R2.pgm")),
                MixCatalogEntry("Island", "Island.mix", MixLifecycle.ACTIVE, programs = listOf("R3.pgm", "R1.pgm")),
                MixCatalogEntry("Old", "Old.mix", MixLifecycle.HISTORY, programs = listOf("R1.pgm"))
            )
        )

        val cards = jobDetailMaterialCards(material, catalog, trackablePages = listOf(1, 2, 3))

        assertEquals(listOf("Base - 19mm", "Island - 19mm"), cards.map { it.title })
        assertEquals(listOf(listOf(2), listOf(3, 1)), cards.map { it.pages })
    }

    @Test
    fun `no active mix keeps one card with every trackable page`() {
        val material = Material(pdfFilename = "19mm.pdf", materialName = "19mm", pageCount = 3)
        val catalog = MixCatalogSnapshot(
            job = "648 - WIECHERT",
            material = "19mm",
            revision = 2,
            entries = listOf(MixCatalogEntry("Old", "Old.mix", MixLifecycle.HISTORY, programs = listOf("R1.pgm")))
        )

        val cards = jobDetailMaterialCards(material, catalog, trackablePages = listOf(1, 3))

        assertEquals(listOf("19mm"), cards.map { it.title })
        assertEquals(listOf(1, 3), cards.single().pages)
    }

    @Test
    fun `cached catalogs are available before the per material refresh plan`() {
        val first = Material(pdfFilename = "19mm.pdf", materialName = "19mm", pageCount = 1)
        val second = Material(pdfFilename = "12mm.pdf", materialName = "12mm", pageCount = 1)
        val cached = MixCatalogSnapshot(
            job = "648 - WIECHERT",
            material = "19mm",
            revision = 2,
            entries = emptyList()
        )

        val plan = cachedFirstCatalogLoadPlan(listOf(first, second)) { material ->
            if (material == first) cached else null
        }

        assertEquals(cached, plan.cachedCatalogs["19mm.pdf"])
        assertEquals(null, plan.cachedCatalogs["12mm.pdf"])
        assertEquals(listOf(first, second), plan.refreshMaterials)
    }
}
