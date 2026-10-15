package com.kkc.sheettracker.ui.components

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class PdfPagePreviewSizingTest {
    @Test fun scrollingThumbnailsDecodeAtSmallDisplaySize() {
        assertEquals(IntSize(340, 440), pdfPreviewDecodeSize(1980, 2560, IntSize(340, Int.MAX_VALUE)))
    }

    @Test fun fittedPagesPreserveAspectRatioAndDoNotUpscale() {
        assertEquals(IntSize(1080, 1396), pdfPreviewDecodeSize(1980, 2560, IntSize(1080, 1600)))
        assertEquals(IntSize(1980, 2560), pdfPreviewDecodeSize(1980, 2560, IntSize(4000, 4000)))
        assertEquals(IntSize(1188, 1536), pdfPreviewDecodeSize(1980, 2560, IntSize(1080, 1600), 1.1f))
    }

    @Test fun invalidDimensionsAreRejectedAndLargeDecodesAreCapped() {
        assertNull(pdfPreviewDecodeSize(0, 100, IntSize(100, 100)))
        assertNull(pdfPreviewDecodeSize(100, 100, IntSize.Zero))
        assertNull(pdfPreviewDecodeSize(100, 100, IntSize(100, 100), Float.NaN))
        val capped = pdfPreviewDecodeSize(20000, 20000, IntSize(20000, 20000))!!
        assertTrue(capped.width.toLong() * capped.height <= 8_000_000L)
    }
}
