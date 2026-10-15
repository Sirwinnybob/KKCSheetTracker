package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContinuousPdfTinyPreviewsTest {
    @Test fun prefetchStartsAtOpeningPageAndAlternatesOutward() {
        assertEquals(listOf(4, 5, 3, 6, 2, 7, 1), tinyPreviewPrefetchOrder(4, 7))
    }

    @Test fun prefetchCoversEveryPageExactlyOnceFromAnyStart() {
        for (start in listOf(1, 2, 9, 10)) {
            val order = tinyPreviewPrefetchOrder(start, 10)
            assertEquals((1..10).toList(), order.sorted())
            assertEquals(start, order.first())
        }
    }

    @Test fun prefetchClampsStartAndHandlesEmptyDocuments() {
        assertEquals(listOf(1, 2, 3), tinyPreviewPrefetchOrder(-5, 3))
        assertEquals(listOf(3, 2, 1), tinyPreviewPrefetchOrder(99, 3))
        assertEquals(emptyList<Int>(), tinyPreviewPrefetchOrder(1, 0))
    }

    @Test fun lightAndDarkVariantsOfAPageDoNotShareAPreview() {
        val light = tinyPreviewKey("/job/a.pdf", "a.pdf", 3, false)
        val dark = tinyPreviewKey("/job/DARK MODE/a.pdf", "a.pdf", 3, true)
        assertNotEquals(light, dark)
        assertNotEquals(tinyPreviewKey("/job/a.pdf", "a.pdf", 3, false), tinyPreviewKey("/job/a.pdf", "a.pdf", 4, false))
    }
}
