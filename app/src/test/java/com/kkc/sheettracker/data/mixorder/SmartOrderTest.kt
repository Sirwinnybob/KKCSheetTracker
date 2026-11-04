package com.kkc.sheettracker.data.mixorder

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartOrderTest {
    private fun sheet(head: Int, smallEdge: Int = 0, smallInterior: Int = 0) = SmartSheet(
        headPage = head,
        pgms = listOf("R$head"),
        features = SheetFeatures(smallEdge, smallInterior, 0.0, 10.0, smallEdge + smallInterior + 1)
    )

    private fun order(vararg sheets: SmartSheet) = smartOrder(sheets.toList(), PRIOR_WEIGHTS).map { it.headPage }

    @Test
    fun priorPutsMostEdgePartsFirst() {
        assertEquals(listOf(2, 3, 1), order(sheet(1, smallEdge = 0), sheet(2, smallEdge = 4), sheet(3, smallEdge = 2)))
    }

    @Test
    fun priorTieBreaksOnInterior() {
        assertEquals(listOf(2, 3, 1), order(
            sheet(1, smallEdge = 2, smallInterior = 0),
            sheet(2, smallEdge = 2, smallInterior = 5),
            sheet(3, smallEdge = 2, smallInterior = 3)
        ))
    }

    @Test
    fun equalScoresKeepPageOrder() {
        assertEquals(listOf(1, 2, 3), order(sheet(1, 1, 1), sheet(2, 1, 1), sheet(3, 1, 1)))
    }

    @Test
    fun scoreIsDotProduct() {
        val f = SheetFeatures(smallEdge = 3, smallInterior = 2, smallEdgeArea = 0.5, minDim = 4.0, partCount = 10)
        assertEquals(3.002, PRIOR_WEIGHTS.score(f), 1e-9)
        // 3*1 + 2*0 + 0.5*2 + 4*3 + 10*4
        assertEquals(56.0, MixOrderWeights(listOf(1.0, 0.0, 2.0, 3.0, 4.0)).score(f), 1e-9)
    }

    @Test
    fun smartTagFormat() {
        val f = SheetFeatures(smallEdge = 3, smallInterior = 2, smallEdgeArea = 0.0, minDim = 1.0, partCount = 5)
        assertEquals("5 small · 3 on edge", smartTag(f))
    }
}
