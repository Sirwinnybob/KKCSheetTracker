package com.kkc.sheettracker.data

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

/**
 * The prepared-diagram cache holds full CNC sheet diagrams. Those bitmaps are tens of MB each,
 * so the cache must be bounded by bytes, not just entry count (field log 2026-09-29:
 * RSS 1.1-1.36 GB on SM-X808U while paging a CNC mix).
 */
class PreparedDiagramCacheTest {

    private fun store(): ProgressStore {
        val baseDir = Files.createTempDirectory("prepared-diagram").toFile()
        return ProgressStore(baseDir, "tablet-test", File(baseDir, ".local"))
    }

    private fun key(page: Int) = PreparedPageKey(
        jobFolderName = "684 - Test",
        pdfFilename = "19mm.pdf",
        page = page,
        fileFingerprint = "fp"
    )

    private fun bitmapOf(bytes: Int): Bitmap = mock<Bitmap>().also {
        whenever(it.allocationByteCount).thenReturn(bytes)
    }

    @Test
    fun evictsOldestDiagramsOnceByteBudgetIsExceeded() = runBlocking {
        val store = store()
        val perDiagram = 40 * 1024 * 1024
        val budget = ProgressStore.PREPARED_CACHE_MAX_BYTES
        val fitting = (budget / perDiagram).toInt()
        val total = fitting + 2

        for (page in 1..total) {
            store.getOrPrepareDiagram(key(page), "test") { PreparedDiagram(bitmapOf(perDiagram)) }
        }

        assertNull(store.getPreparedPageEntry(key(1)))
        assertNull(store.getPreparedPageEntry(key(2)))
        for (page in 3..total) {
            assertNotNull("page $page should still be cached", store.getPreparedPageEntry(key(page)))
        }
    }

    @Test
    fun keepsSingleDiagramLargerThanBudget() = runBlocking {
        val store = store()
        val huge = (ProgressStore.PREPARED_CACHE_MAX_BYTES + 1).toInt()

        store.getOrPrepareDiagram(key(1), "test") { PreparedDiagram(bitmapOf(huge)) }

        assertNotNull(store.getPreparedPageEntry(key(1)))
    }

    @Test
    fun cacheHitReturnsSourceScaleOfPreparedDiagram() = runBlocking {
        val store = store()
        val bitmap = bitmapOf(1024)

        store.getOrPrepareDiagram(key(1), "test") { PreparedDiagram(bitmap, sourceScale = 0.5f) }
        val hit = store.getOrPrepareDiagram(key(1), "test") { error("producer must not run on a hit") }

        assertSame(bitmap, hit?.bitmap)
        assertEquals(0.5f, hit!!.sourceScale, 0.0001f)
    }
}
