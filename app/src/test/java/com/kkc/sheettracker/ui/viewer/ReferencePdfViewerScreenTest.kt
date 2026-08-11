package com.kkc.sheettracker.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePdfViewerScreenTest {

    @Test
    fun fullscreenTap_withoutWakeMarkerRetainsNormalToggleBehavior() {
        val result = applyFullscreenSingleTap(showUi = true, wakePending = false)

        assertFalse(result.showUi)
        assertFalse(result.wakePending)
    }

    @Test
    fun fullscreenTap_withWakeMarkerForcesControlsVisibleAndConsumesMarker() {
        val result = applyFullscreenSingleTap(showUi = false, wakePending = true)

        assertTrue(result.showUi)
        assertFalse(result.wakePending)
    }
}
