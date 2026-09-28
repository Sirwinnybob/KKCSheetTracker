package com.kkc.sheettracker.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.kkc.sheettracker.ui.theme.KKCThemePalette
import com.kkc.sheettracker.ui.theme.LightStatusColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KKCSlidingPillTest {

    private val seahawksLike = KKCThemePalette(
        primary = Color(0xFF002244),
        secondary = Color(0xFF69BE28),
        background = Color.White,
        surface = Color.White
    )
    private val singleColor = seahawksLike.copy(secondary = null)
    private val onSurface = Color(0xFF162236)
    private val surface = Color.White
    private val outline = Color(0xFFC6D3E2)

    private fun style(
        palette: KKCThemePalette,
        bold: Boolean = false,
        accent: KKCPillAccent = KKCPillAccent.PRIMARY
    ) = kkcPillStyle(palette, bold, LightStatusColors, onSurface, surface, outline, accent)

    @Test
    fun secondaryThemesUsePrimaryAsTrackAndSecondaryAsPill() {
        val s = style(seahawksLike)
        assertTrue(s.filledContainer)
        assertEquals(seahawksLike.primary, s.container)
        assertEquals(SolidColor(seahawksLike.secondary!!), s.fill)
    }

    @Test
    fun secondaryThemeLabelsContrastWithTheirBackground() {
        val s = style(seahawksLike)
        assertEquals(Color.White, s.unselectedText) // on dark navy track
        assertEquals(Color.Black, s.selectedText) // on light green pill
    }

    @Test
    fun boldModeDoesNotChangeTwoColorLayout() {
        assertEquals(style(seahawksLike), style(seahawksLike, bold = true))
    }

    @Test
    fun singleColorThemesKeepNeutralTrackWithPrimaryTintPill() {
        val s = style(singleColor)
        assertFalse(s.filledContainer)
        assertEquals(surface, s.container)
        assertEquals(SolidColor(singleColor.primary.copy(alpha = 0.18f)), s.fill)
        assertEquals(onSurface, s.selectedText)
    }

    @Test
    fun attentionPillFollowsThemedStatusSkipColorInBothLayouts() {
        val two = style(seahawksLike, accent = KKCPillAccent.ATTENTION)
        assertEquals(SolidColor(LightStatusColors.skip), two.fill)
        assertEquals(seahawksLike.primary, two.container)

        val one = style(singleColor, accent = KKCPillAccent.ATTENTION)
        assertEquals(SolidColor(LightStatusColors.skip.copy(alpha = 0.2f)), one.fill)
        assertEquals(LightStatusColors.skip, one.unselectedText)
    }
}
