package com.kkc.sheettracker.ui.supply

import com.kkc.sheettracker.ui.dashboard.DashboardAccent
import org.junit.Assert.assertEquals
import org.junit.Test

class SupplyStatusAccentTest {

    @Test
    fun criticalAndUrgentTiersMapToDanger() {
        assertEquals(DashboardAccent.DANGER, supplyTierAccent(1))
        assertEquals(DashboardAccent.DANGER, supplyTierAccent(2))
    }

    @Test
    fun lowAndNotOrderedTiersMapToWarning() {
        assertEquals(DashboardAccent.WARNING, supplyTierAccent(3))
        assertEquals(DashboardAccent.WARNING, supplyTierAccent(6))
    }

    @Test
    fun orderedTierMapsToInfo() {
        assertEquals(DashboardAccent.INFO, supplyTierAccent(4))
    }

    @Test
    fun inStockAndToOrderTiersMapToSuccess() {
        assertEquals(DashboardAccent.SUCCESS, supplyTierAccent(5))
        assertEquals(DashboardAccent.SUCCESS, supplyTierAccent(7))
    }

    @Test
    fun unknownTierDefaultsToSuccess() {
        assertEquals(DashboardAccent.SUCCESS, supplyTierAccent(99))
    }
}
