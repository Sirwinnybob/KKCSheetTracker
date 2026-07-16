package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.models.ReferenceDocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceModalStateTest {

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
}
