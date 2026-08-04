package com.kkc.sheettracker.ui.viewer

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.math.abs

class SheetViewerScreenTest {

    @Test
    fun sheetRenderQuality_usesHalfScaleForAdjacentAndFullScaleForCurrent() {
        assertEquals(0.5f, SheetRenderQuality.ADJACENT.scale, 0.0001f)
        assertEquals(1f, SheetRenderQuality.CURRENT.scale, 0.0001f)
        assertEquals(SheetDiagramSource.SIDECAR_THUMBNAIL, SheetRenderQuality.ADJACENT.diagramSource)
        assertEquals(SheetDiagramSource.FULL_EMBEDDED_IMAGE, SheetRenderQuality.CURRENT.diagramSource)
    }

    @Test
    fun cachedAdjacentRender_isPromotedWhenPageBecomesCurrent() {
        assertFalse(isRenderQualitySufficient(0.5f, SheetRenderQuality.CURRENT))
        assertTrue(isRenderQualitySufficient(1f, SheetRenderQuality.CURRENT))
        assertTrue(isRenderQualitySufficient(1f, SheetRenderQuality.ADJACENT))
    }

    @Test
    fun resolveSheetDisplayBitmap_neverFallsBackToFullPageInDiagramMode() {
        val fullPage = mock<Bitmap>()
        val diagram = mock<Bitmap>()

        assertEquals(diagram, resolveSheetDisplayBitmap(false, fullPage, diagram))
        assertNull(resolveSheetDisplayBitmap(false, fullPage, null))
        assertEquals(fullPage, resolveSheetDisplayBitmap(true, fullPage, diagram))
    }

    private fun screenPositionOf(
        contentX: Float,
        contentY: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
        viewSize: IntSize
    ): Offset {
        val centerX = viewSize.width / 2f
        val centerY = viewSize.height / 2f
        return Offset(
            centerX + zoom * (contentX - centerX) + panX,
            centerY + zoom * (contentY - centerY) + panY
        )
    }

    @Test
    fun computeAnchoredZoomPan_keepsContentUnderPinchCentroidFixed() {
        val viewSize = IntSize(width = 1000, height = 800)
        val centroid = Offset(200f, 150f) // far from the view center (500, 400)
        val zoom = 1f
        val panX = 0f
        val panY = 0f

        // The content point currently sitting under the fingers, before this zoom step.
        val centerX = viewSize.width / 2f
        val centerY = viewSize.height / 2f
        val contentUnderFinger = Offset(
            centerX + (centroid.x - centerX - panX) / zoom,
            centerY + (centroid.y - centerY - panY) / zoom
        )

        val result = computeAnchoredZoomPan(
            zoom = zoom,
            panX = panX,
            panY = panY,
            zoomChange = 1.5f,
            panChange = Offset.Zero,
            centroid = centroid,
            viewSize = viewSize,
            minZoom = 1f,
            maxZoom = 5f
        )

        val screenPositionAfter = screenPositionOf(
            contentUnderFinger.x, contentUnderFinger.y, result.zoom, result.panX, result.panY, viewSize
        )

        // Fingers didn't move (panChange = 0), so the same content point must still
        // appear under the centroid -- not drift toward the view's center.
        assertTrue(abs(screenPositionAfter.x - centroid.x) < 0.01f)
        assertTrue(abs(screenPositionAfter.y - centroid.y) < 0.01f)
    }

    @Test
    fun computeAnchoredZoomPan_isUnaffectedWhenNotZooming() {
        // Pure single-finger pan (zoomChange = 1) must behave exactly like before:
        // pan accumulates the finger delta with no anchor correction applied.
        val result = computeAnchoredZoomPan(
            zoom = 2f,
            panX = 10f,
            panY = -5f,
            zoomChange = 1f,
            panChange = Offset(7f, 3f),
            centroid = Offset(123f, 456f),
            viewSize = IntSize(1000, 800),
            minZoom = 1f,
            maxZoom = 5f
        )

        assertEquals(2f, result.zoom, 0.0001f)
        assertEquals(17f, result.panX, 0.0001f)
        assertEquals(-2f, result.panY, 0.0001f)
    }

    @Test
    fun computeAnchoredZoomPan_remainsCorrectWhenClampedAtMaxZoom() {
        val viewSize = IntSize(width = 1000, height = 800)
        val centroid = Offset(900f, 700f)
        val zoom = 4.9f

        val centerX = viewSize.width / 2f
        val centerY = viewSize.height / 2f
        val contentUnderFinger = Offset(
            centerX + (centroid.x - centerX) / zoom,
            centerY + (centroid.y - centerY) / zoom
        )

        val result = computeAnchoredZoomPan(
            zoom = zoom,
            panX = 0f,
            panY = 0f,
            zoomChange = 2f, // would overshoot maxZoom = 5
            panChange = Offset.Zero,
            centroid = centroid,
            viewSize = viewSize,
            minZoom = 1f,
            maxZoom = 5f
        )

        assertEquals(5f, result.zoom, 0.0001f)

        val screenPositionAfter = screenPositionOf(
            contentUnderFinger.x, contentUnderFinger.y, result.zoom, result.panX, result.panY, viewSize
        )
        assertTrue(abs(screenPositionAfter.x - centroid.x) < 0.01f)
        assertTrue(abs(screenPositionAfter.y - centroid.y) < 0.01f)
    }

    @Test
    fun shouldShowPenMarkupOverlay_requiresOnlyPenMode() {
        assertFalse(shouldShowPenMarkupOverlay(showFullPdfPage = false, penModeEnabled = false))
        assertTrue(shouldShowPenMarkupOverlay(showFullPdfPage = false, penModeEnabled = true))
        assertFalse(shouldShowPenMarkupOverlay(showFullPdfPage = true, penModeEnabled = false))
        assertTrue(shouldShowPenMarkupOverlay(showFullPdfPage = true, penModeEnabled = true))
    }

    @Test
    fun resolveSheetViewerMarkupStoreConfig_returnsNullWhenPrefsMissing() {
        assertNull(resolveSheetViewerMarkupStoreConfig(basePath = null, tabletId = "tablet-1"))
        assertNull(resolveSheetViewerMarkupStoreConfig(basePath = "C:/Jobs", tabletId = null))
        assertNull(resolveSheetViewerMarkupStoreConfig(basePath = "   ", tabletId = "tablet-1"))
        assertNull(resolveSheetViewerMarkupStoreConfig(basePath = "C:/Jobs", tabletId = "   "))
    }

    @Test
    fun resolveSheetViewerMarkupStoreConfig_trimsAndReturnsConfig() {
        val config = resolveSheetViewerMarkupStoreConfig(
            basePath = " C:/Jobs/Base ",
            tabletId = " tablet-7 "
        )

        assertNotNull(config)
        assertEquals("C:/Jobs/Base", config?.basePath)
        assertEquals("tablet-7", config?.tabletId)
    }

    @Test
    fun cncSheetViewerUiVisible_alwaysKeepsControlsVisible() {
        assertTrue(cncSheetViewerUiVisible())
    }

    @Test
    fun cncSheetViewerTitle_putsJobNumberBeforeMaterialName() {
        assertEquals("12345 - Maple", cncSheetViewerTitle("12345", "Maple"))
    }

    @Test
    fun cncSheetViewerTitle_fallsBackToMaterialWhenJobNumberMissing() {
        assertEquals("Maple", cncSheetViewerTitle(null, "Maple"))
        assertEquals("Maple", cncSheetViewerTitle("   ", "Maple"))
    }

    @Test
    fun partMarkers_returnsIndependentRotationAndBandingMarkers() {
        assertEquals(emptyList<PartMarker>(), partMarkers(rotated = false, banding = null))
        assertEquals(listOf(PartMarker.Rotation), partMarkers(rotated = true, banding = null))
        assertEquals(listOf(PartMarker.Banding), partMarkers(rotated = false, banding = "2WD2LD"))
        assertEquals(listOf(PartMarker.Rotation, PartMarker.Banding), partMarkers(rotated = true, banding = "2WD2LD"))
        assertEquals(emptyList<PartMarker>(), partMarkers(rotated = false, banding = "   "))
    }

    @Test
    fun resolveCncSidecarFile_returnsNullForBlankPath() {
        val pdfFile = File("C:/Ready Jobs/597b - TEST JOB/CNC/597b - Material.pdf")

        assertNull(resolveCncSidecarFile(pdfFile, null))
        assertNull(resolveCncSidecarFile(pdfFile, "   "))
    }

    @Test
    fun resolveCncSidecarFile_resolvesRelativePathFromPdfParent() {
        val pdfFile = File("C:/Ready Jobs/597b - TEST JOB/CNC/597b - Material.pdf")

        val resolved = resolveCncSidecarFile(
            pdfFile,
            ".metadata/parts/597b - Material_p001_part001.jpeg"
        )

        assertEquals(
            File("C:/Ready Jobs/597b - TEST JOB/CNC/.metadata/parts/597b - Material_p001_part001.jpeg"),
            resolved
        )
    }

    @Test
    fun resolveCncSidecarFile_keepsAbsolutePath() {
        val pdfFile = File("C:/Ready Jobs/597b - TEST JOB/CNC/597b - Material.pdf")
        val absolute = File("D:/cache/part.png")

        assertEquals(absolute, resolveCncSidecarFile(pdfFile, absolute.path))
    }

    @Test
    fun shouldRecycleRenderedPageBitmap_falseWhenBitmapIsNull() {
        assertFalse(shouldRecycleRenderedPageBitmap(bitmap = null, wasDisplayed = false))
    }

    @Test
    fun shouldRecycleRenderedPageBitmap_falseOnceBitmapReachedCompose() {
        val bitmap = mock<Bitmap>()
        whenever(bitmap.isRecycled).thenReturn(false)

        assertFalse(shouldRecycleRenderedPageBitmap(bitmap = bitmap, wasDisplayed = true))
    }

    @Test
    fun shouldRecycleRenderedPageBitmap_falseWhenAlreadyRecycled() {
        val bitmap = mock<Bitmap>()
        whenever(bitmap.isRecycled).thenReturn(true)

        assertFalse(shouldRecycleRenderedPageBitmap(bitmap = bitmap, wasDisplayed = false))
    }

    @Test
    fun shouldRecycleRenderedPageBitmap_trueWhenNeverDisplayedAndNotRecycled() {
        val bitmap = mock<Bitmap>()
        whenever(bitmap.isRecycled).thenReturn(false)

        assertTrue(shouldRecycleRenderedPageBitmap(bitmap = bitmap, wasDisplayed = false))
    }
}
