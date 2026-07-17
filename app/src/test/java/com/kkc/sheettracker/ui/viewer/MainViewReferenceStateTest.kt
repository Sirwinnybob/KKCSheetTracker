package com.kkc.sheettracker.ui.viewer

import com.kkc.sheettracker.data.models.ReferenceDocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainViewReferenceStateTest {

    @Test
    fun pageForMode_returnsPerModePage() {
        val snap = MainViewReferenceSnapshot(mode = ReferenceDocType.PLANS_ELEVATIONS, plansPage = 3, assemblyPage = 8)
        assertEquals(3, snap.pageForMode())
        assertEquals(8, snap.copy(mode = ReferenceDocType.ASSEMBLY).pageForMode())
    }

    @Test
    fun pageForMode_defaultsToPlansPageWhenModeIsNull() {
        val snap = MainViewReferenceSnapshot(mode = null, plansPage = 3, assemblyPage = 8)
        assertEquals(3, snap.pageForMode())
    }

    @Test
    fun withMode_switchesAndKeepsOtherModePage() {
        val snap = MainViewReferenceSnapshot(mode = ReferenceDocType.PLANS_ELEVATIONS, plansPage = 3, assemblyPage = 8)
        val switched = snap.withMode(ReferenceDocType.ASSEMBLY)
        assertEquals(ReferenceDocType.ASSEMBLY, switched.mode)
        assertEquals(3, switched.plansPage)
        assertEquals(8, switched.assemblyPage)
    }

    @Test
    fun withMode_toNullReturnsToSheet() {
        val snap = MainViewReferenceSnapshot(mode = ReferenceDocType.ASSEMBLY, plansPage = 3, assemblyPage = 8)
        val switched = snap.withMode(null)
        assertNull(switched.mode)
    }

    @Test
    fun withPage_updatesOnlyActiveModePage() {
        val snap = MainViewReferenceSnapshot(mode = ReferenceDocType.ASSEMBLY, plansPage = 3, assemblyPage = 8)
        val updated = snap.withPage(15)
        assertEquals(3, updated.plansPage)
        assertEquals(15, updated.assemblyPage)
    }
}
