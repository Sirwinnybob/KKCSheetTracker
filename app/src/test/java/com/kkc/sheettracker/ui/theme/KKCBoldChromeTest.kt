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

    @Test
    fun boldChipTextColorIsBlackOnLightGradient() {
        val lightPalette = KKCThemePalette(
            primary = Color(0xFFFFB612),
            secondary = Color(0xFFFFFFFF),
            background = Color.White,
            surface = Color.White
        )
        assertEquals(Color.Black, boldChipTextColor(lightPalette))
    }

    @Test
    fun boldChipTextColorIsWhiteOnDarkGradient() {
        val darkPalette = KKCThemePalette(
            primary = Color(0xFFE31837),
            secondary = Color(0xFF000000),
            background = Color.White,
            surface = Color.White
        )
        assertEquals(Color.White, boldChipTextColor(darkPalette))
    }

    @Test
    fun boldChipTextColorIsBlackWhenPrimaryIsLightEvenWithDarkSecondary() {
        // Steelers-like: light gold primary, black secondary. Averaging the two (the old
        // implementation) put the blend under the black/white threshold and picked white —
        // unreadable on the gold corner where this text is actually positioned.
        val steelersLike = KKCThemePalette(
            primary = Color(0xFFFFB612),
            secondary = Color(0xFF101820),
            background = Color.White,
            surface = Color.White
        )
        assertEquals(Color.Black, boldChipTextColor(steelersLike))
    }

    @Test
    fun boldChipTextColorIsWhiteWhenPrimaryIsDarkEvenWithLightSecondary() {
        // Chiefs-like: dark red primary, light gold secondary. Text sits on the dark primary
        // corner, so white must win regardless of the lighter secondary.
        val chiefsLike = KKCThemePalette(
            primary = Color(0xFFE31837),
            secondary = Color(0xFFFFB612),
            background = Color.White,
            surface = Color.White
        )
        assertEquals(Color.White, boldChipTextColor(chiefsLike))
    }

    // --- Non-composable frosted color overloads (used by the Hours Tracker navbar payload builder,
    // which resolves BOTH light and dark sets regardless of the current KKC mode). ---

    private val boldTokens = BuiltInKKCThemeTokens.copy(
        boldMode = true,
        light = KKCThemePalette(
            primary = Color(0xFFFFB612),
            secondary = Color(0xFFFFFFFF),
            background = Color.White,
            surface = Color.White
        ),
        dark = KKCThemePalette(
            primary = Color(0xFF101820),
            secondary = Color(0xFF000000),
            background = Color.Black,
            surface = Color(0xFF101820)
        )
    )

    @Test
    fun frostedContentColorIsBlackOnBrightBoldGlassInLightMode() {
        val scheme = boldTokens.toColorScheme(darkTheme = false)
        assertEquals(Color.Black, kkcFrostedContentColor(boldTokens, scheme, darkTheme = false))
    }

    @Test
    fun frostedContentColorIsWhiteOnDarkBoldGlassInDarkMode() {
        val scheme = boldTokens.toColorScheme(darkTheme = true)
        assertEquals(Color.White, kkcFrostedContentColor(boldTokens, scheme, darkTheme = true))
    }

    @Test
    fun frostedContentColorIsOnSurfaceWhenNotBold() {
        val tokens = BuiltInKKCThemeTokens
        listOf(false, true).forEach { dark ->
            val scheme = tokens.toColorScheme(dark)
            assertEquals(scheme.onSurface, kkcFrostedContentColor(tokens, scheme, dark))
        }
    }

    @Test
    fun frostedBaseColorIsSurfaceWhenNotBoldAndGlowWhenBold() {
        val plain = BuiltInKKCThemeTokens
        val plainScheme = plain.toColorScheme(darkTheme = true)
        assertEquals(plainScheme.surface, kkcFrostedBaseColor(plain, plainScheme, darkTheme = true))

        val boldScheme = boldTokens.toColorScheme(darkTheme = false)
        assertEquals(
            boldGlowColor(boldTokens.palette(false), alpha = 1f),
            kkcFrostedBaseColor(boldTokens, boldScheme, darkTheme = false)
        )
    }
}
