package com.kkc.sheettracker.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class KKCBoldChromeTest {

    private val paletteWithSecondary = KKCThemePalette(
        primary = Color(0xFFE31837),
        secondary = Color(0xFFFFB612),
        background = Color.White,
        surface = Color.White
    )

    private val paletteWithoutSecondary = KKCThemePalette(
        primary = Color(0xFFE31837),
        secondary = null,
        background = Color.White,
        surface = Color.White
    )

    @Test
    fun boldGradientColorsUsesPrimaryAndSecondaryWhenBothSet() {
        assertEquals(listOf(Color(0xFFE31837), Color(0xFFFFB612)), boldGradientColors(paletteWithSecondary))
    }

    @Test
    fun boldGradientColorsFallsBackToPrimaryTwiceWhenSecondaryAbsent() {
        assertEquals(listOf(Color(0xFFE31837), Color(0xFFE31837)), boldGradientColors(paletteWithoutSecondary))
    }

    @Test
    fun boldGlowColorBlendsMidpointWhenSecondarySet() {
        val glow = boldGlowColor(paletteWithSecondary, alpha = 0.3f)
        val expectedBase = androidx.compose.ui.graphics.lerp(Color(0xFFE31837), Color(0xFFFFB612), 0.5f)
        assertEquals(expectedBase.copy(alpha = 0.3f), glow)
    }

    @Test
    fun boldGlowColorUsesPrimaryWhenSecondaryAbsent() {
        val glow = boldGlowColor(paletteWithoutSecondary, alpha = 0.4f)
        assertEquals(Color(0xFFE31837).copy(alpha = 0.4f), glow)
    }
}
