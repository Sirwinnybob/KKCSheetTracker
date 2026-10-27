package com.kkc.sheettracker.ui.viewer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import com.kkc.sheettracker.data.models.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetPartInfoPopupTest {
    @Test fun rotationStarStaysRedUnlessPopupItselfHasARedTint() {
        for (background in listOf(Color.White, Color.Black, Color.Green, Color.Blue,
            Color(0xFF6A1B9A), Color(0xFFFF8800))) {
            assertEquals(Color.Red, sheetPartRotationColor(background, Color.Black))
        }
        assertEquals(Color.Black, sheetPartRotationColor(Color(0xFFBA0C2F), Color.Black))
        assertEquals(Color.White, sheetPartRotationColor(Color(0xFF770011), Color.White))
        assertEquals(Color.Black, sheetPartRotationColor(Color(0xFFFF7777), Color.Black))
    }

    @Test fun formatsShopDetailsWithoutRoundingDimensions() {
        val part = Part(cabNumber = 6, name = "Unfinished Right End", width = 12.875,
            length = 27.5, room = "PANTRY", rotated = true, banding = "2WD2LD")
        assertEquals("Cab# 6 - Unfinished Right End - W:12.875 L:27.5 - PANTRY", sheetPartInfoText(part))
        assertEquals("Cab# 6 - Unfinished Right End - W:12 L:27",
            sheetPartInfoText(part.copy(width = 12.0, length = 27.0, room = "  ")))
    }

    @Test fun placesPopupBesidePartInAvailableSpace() {
        val anchor = Rect(300f, 200f, 500f, 400f)
        val occupied = listOf(anchor, Rect(250f, 0f, 600f, 190f))
        val position = sheetPartPopupPosition(IntSize(800, 600), IntSize(240, 90), anchor, occupied, 8f)
        val popup = Rect(position.x.toFloat(), position.y.toFloat(), position.x + 240f, position.y + 90f)
        occupied.forEach { assertFalse("Popup covers a part: $popup", popup.overlaps(it)) }
        assertTrue(popup.left >= 8f && popup.right <= 792f)
        assertTrue(popup.top >= 8f && popup.bottom <= 592f)
    }

    @Test fun clampsPopupWhenPartIsAtAnEdgeOrOutsideZoomedViewport() {
        for (anchor in listOf(Rect(770f, 570f, 800f, 600f), Rect(-400f, -200f, -100f, -50f))) {
            val position = sheetPartPopupPosition(IntSize(800, 600), IntSize(300, 100), anchor, listOf(anchor), 8f)
            assertTrue(position.x in 8..492)
            assertTrue(position.y in 8..492)
        }
    }

    @Test fun packedSheetKeepsPopupBesideSelectedPartInsteadOfSeekingDistantEmptySpace() {
        val anchor = Rect(1050f, 450f, 1190f, 590f)
        val occupied = listOf(anchor, Rect(550f, 300f, 1040f, 690f))
        val position = sheetPartPopupPosition(IntSize(1200, 700), IntSize(500, 90), anchor, occupied, 8f)
        val popup = Rect(position.x.toFloat(), position.y.toFloat(), position.x + 500f, position.y + 90f)
        val horizontalGap = maxOf(anchor.left - popup.right, popup.left - anchor.right, 0f)
        val verticalGap = maxOf(anchor.top - popup.bottom, popup.top - anchor.bottom, 0f)
        assertTrue("Popup wandered away from selected part: $popup",
            horizontalGap * horizontalGap + verticalGap * verticalGap <= 16f * 16f)
        assertFalse("Popup covers selected part", popup.overlaps(anchor))
    }

    @Test fun supportsMissingGeometryAndTinyPreview() {
        assertEquals(androidx.compose.ui.unit.IntOffset(8, 8),
            sheetPartPopupPosition(IntSize(800, 600), IntSize(300, 100), null, emptyList(), 8f))
        val position = sheetPartPopupPosition(IntSize(40, 30), IntSize(40, 30), null, emptyList(), 8f)
        assertEquals(androidx.compose.ui.unit.IntOffset.Zero, position)
    }
}
