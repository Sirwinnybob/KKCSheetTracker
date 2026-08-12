package com.kkc.sheettracker.ui.supply

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplyActivationRefreshTest {

    @Test
    fun `returning to Supply requires a disk reload`() {
        assertTrue(shouldReloadSupplyOnActivation(wasActive = false, isActive = true))
    }

    @Test
    fun `remaining on Supply does not trigger a redundant reload`() {
        assertFalse(shouldReloadSupplyOnActivation(wasActive = true, isActive = true))
    }

    @Test
    fun `leaving Supply does not trigger a reload`() {
        assertFalse(shouldReloadSupplyOnActivation(wasActive = true, isActive = false))
    }
}
