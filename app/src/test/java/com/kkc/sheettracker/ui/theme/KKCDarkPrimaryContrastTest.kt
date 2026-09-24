package com.kkc.sheettracker.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KKCDarkPrimaryContrastTest {

    private val darkSurface = Color(0xFF162438)

    // Dark-mode primaries from the synced NFL themes that were unreadable on the dark surface.
    private val unreadablePrimaries = listOf(
        0xFF002244, 0xFF0B162A, 0xFF00338D, 0xFF311D00, 0xFFD50A0A, 0xFF97233F, 0xFF002C5F,
        0xFF5A1414, 0xFF041E42, 0xFF004C54, 0xFFA71930, 0xFF0B2265, 0xFF101820, 0xFF125740,
        0xFF203731, 0xFF000000, 0xFF003594, 0xFF241773, 0xFF03202F, 0xFF0C2340, 0xFF4F2683,
        0xFFAA0000
    ).map { Color(it) }

    @Test
    fun lowContrastPrimariesAreLiftedToReadable() {
        unreadablePrimaries.forEach { primary ->
            val lifted = readableDarkPrimary(primary, darkSurface)
            assertTrue(
                "primary $primary lifted to $lifted is still unreadable",
                contrastRatio(lifted, darkSurface) >= MIN_DARK_PRIMARY_CONTRAST
            )
        }
    }

    @Test
    fun liftingKeepsTheHue() {
        val navy = Color(0xFF002244)
        val lifted = readableDarkPrimary(navy, darkSurface)
        assertTrue("hue drifted", abs(hueOf(navy) - hueOf(lifted)) < 3f)
    }

    @Test
    fun readablePrimaryIsUnchanged() {
        val builtIn = BuiltInKKCThemeTokens.dark.primary
        assertEquals(builtIn, readableDarkPrimary(builtIn, BuiltInKKCThemeTokens.dark.surface))
    }

    @Test
    fun darkSchemeUsesLiftedPrimaryAndContrastingOnPrimary() {
        val seahawksDark = KKCThemePalette(
            primary = Color(0xFF002244),
            background = Color.Black,
            surface = darkSurface,
            secondary = Color(0xFF69BE28)
        )
        val scheme = BuiltInKKCThemeTokens.copy(dark = seahawksDark).toColorScheme(darkTheme = true)
        assertTrue(contrastRatio(scheme.primary, darkSurface) >= MIN_DARK_PRIMARY_CONTRAST)
        assertTrue(
            "button text unreadable on primary",
            contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5f
        )
    }

    @Test
    fun lightSchemePrimaryIsUntouched() {
        val seahawksLight = KKCThemePalette(
            primary = Color(0xFF002244),
            background = Color.White,
            surface = Color.White
        )
        val scheme = BuiltInKKCThemeTokens.copy(light = seahawksLight).toColorScheme(darkTheme = false)
        assertEquals(Color(0xFF002244), scheme.primary)
    }
}
