package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.models.ReferenceDocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceModalStateTest {

    @Test
    fun modalSheetBitmapCache_isBoundToFourPages() {
        assertEquals(4, REFERENCE_MODAL_SHEET_CACHE_PAGES)
    }

    @Test
    fun pageForActiveDoc_returnsPerDocPage() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.PLANS_ELEVATIONS,
            plansPage = 4,
            assemblyPage = 9
        )
        assertEquals(4, snap.pageForActiveDoc())
        assertEquals(9, snap.copy(docType = ReferenceDocType.ASSEMBLY).pageForActiveDoc())
    }

    @Test
    fun withDocType_switchesAndKeepsOtherDocPage() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.PLANS_ELEVATIONS,
            plansPage = 4,
            assemblyPage = 9
        )
        val switched = snap.withDocType(ReferenceDocType.ASSEMBLY)
        assertEquals(ReferenceDocType.ASSEMBLY, switched.docType)
        assertEquals(4, switched.plansPage)
        assertEquals(9, switched.assemblyPage)
        assertEquals(9, switched.pageForActiveDoc())
    }

    @Test
    fun withPage_updatesOnlyActiveDoc() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.PLANS_ELEVATIONS,
            plansPage = 4,
            assemblyPage = 9
        )
        val updated = snap.withPage(7)
        assertEquals(7, updated.plansPage)
        assertEquals(9, updated.assemblyPage)
    }

    @Test
    fun resolveJumpPage_returnsFirstPageForCabinet() {
        val map = mapOf("3" to listOf(5, 6), "8" to listOf(11))
        assertEquals(5, resolveJumpPage(map, 3))
        assertEquals(11, resolveJumpPage(map, 8))
    }

    @Test
    fun resolveJumpPage_returnsNullWhenCabinetAbsent() {
        val map = mapOf("3" to listOf(5))
        assertNull(resolveJumpPage(map, 99))
        assertNull(resolveJumpPage(emptyMap(), 3))
    }

    @Test
    fun coerce_keepsCurrentDocWhenAvailable() {
        assertEquals(
            ReferenceDocType.ASSEMBLY,
            coerceDocTypeForOpen(ReferenceDocType.ASSEMBLY, hasPlans = true, hasAssembly = true, ReferenceDocType.PLANS_ELEVATIONS)
        )
    }

    @Test
    fun coerce_switchesToFallbackWhenCurrentUnavailable() {
        assertEquals(
            ReferenceDocType.PLANS_ELEVATIONS,
            coerceDocTypeForOpen(ReferenceDocType.ASSEMBLY, hasPlans = true, hasAssembly = false, ReferenceDocType.PLANS_ELEVATIONS)
        )
    }

    @Test
    fun coerce_keepsCurrentWhenNoFallback() {
        assertEquals(
            ReferenceDocType.ASSEMBLY,
            coerceDocTypeForOpen(ReferenceDocType.ASSEMBLY, hasPlans = false, hasAssembly = false, null)
        )
    }

    @Test
    fun coerce_sheetAlwaysAvailable() {
        assertEquals(
            ReferenceDocType.SHEET,
            coerceDocTypeForOpen(ReferenceDocType.SHEET, hasPlans = true, hasAssembly = true, ReferenceDocType.PLANS_ELEVATIONS)
        )
        assertEquals(
            ReferenceDocType.SHEET,
            coerceDocTypeForOpen(ReferenceDocType.SHEET, hasPlans = false, hasAssembly = false, null)
        )
    }

    @Test
    fun pageForActiveDoc_returnsSheetPage() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.SHEET,
            plansPage = 4,
            assemblyPage = 9,
            sheetPage = 12
        )
        assertEquals(12, snap.pageForActiveDoc())
    }

    @Test
    fun withPage_updatesOnlySheetPageWhenDocTypeIsSheet() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.SHEET,
            plansPage = 4,
            assemblyPage = 9,
            sheetPage = 12
        )
        val updated = snap.withPage(20)
        assertEquals(4, updated.plansPage)
        assertEquals(9, updated.assemblyPage)
        assertEquals(20, updated.sheetPage)
    }

    @Test
    fun withDocType_syncsSheetPageWhenProvided() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.PLANS_ELEVATIONS,
            sheetPage = 3
        )
        val switched = snap.withDocType(ReferenceDocType.SHEET, syncPage = 17)
        assertEquals(ReferenceDocType.SHEET, switched.docType)
        assertEquals(17, switched.sheetPage)
    }

    @Test
    fun withDocType_noSyncKeepsExistingSheetPage() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.PLANS_ELEVATIONS,
            sheetPage = 3
        )
        val switched = snap.withDocType(ReferenceDocType.SHEET)
        assertEquals(ReferenceDocType.SHEET, switched.docType)
        assertEquals(3, switched.sheetPage)
    }

    @Test
    fun withDocType_syncPageIgnoredForNonSheetTarget() {
        val snap = ReferenceModalSnapshot(docType = ReferenceDocType.SHEET, plansPage = 4)
        val switched = snap.withDocType(ReferenceDocType.PLANS_ELEVATIONS, syncPage = 99)
        assertEquals(ReferenceDocType.PLANS_ELEVATIONS, switched.docType)
        assertEquals(4, switched.plansPage)
    }
}
