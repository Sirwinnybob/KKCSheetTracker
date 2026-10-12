package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorPreviewPolicyTest {
    @Test
    fun largeDocumentOnlyRequestsVisibleRowsAndTwoNeighbors() {
        val requests = navigatorPreviewPages((1..500).toList(), listOf(100, 101, 102))
        assertEquals(listOf(99, 100, 101, 102, 103, 104, 105), requests)
    }

    @Test
    fun filteredRowsUseTheirActualPageNumbersAndIgnoreInvalidIndices() {
        assertEquals(listOf(4, 19, 80, 205), navigatorPreviewPages(listOf(4, 19, 80, 205), listOf(-1, 1, 99)))
        assertTrue(navigatorPreviewPages(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun beforeFirstLayoutOnlySixPreviewsAreRequested() {
        assertEquals((1..6).toList(), navigatorPreviewPages((1..500).toList(), emptyList()))
    }
}
