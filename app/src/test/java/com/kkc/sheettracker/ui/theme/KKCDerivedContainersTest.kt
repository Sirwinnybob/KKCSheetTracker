package com.kkc.sheettracker.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KKCDerivedContainersTest {

    private val seahawks = KKCThemePalette(
        primary = Color(0xFF002244),
        secondary = Color(0xFF69BE28),
        background = Color.White,
        surface = Color.White
    )

    @Test
    fun defaultThemeContainersArePalerPrimaryNotFixedBlue() {
        val light = BuiltInKKCThemeTokens.toColorScheme(darkTheme = false)
        // The default's secondary is itself pale, so containers are tinted from primary.
        val expected = lerp(BuiltInKKCThemeTokens.light.primary, Color.White, 0.86f)
        assertEquals(expected, light.secondaryContainer)
        assertEquals(expected, light.primaryContainer)
        assertTrue("container stays a pale color", light.primaryContainer.luminance() > 0.7f)
    }

    @Test
    fun twoColorThemeTintsSecondaryContainerFromSecondary() {
        val c = deriveContainers(seahawks, darkTheme = false)
        assertEquals(lerp(seahawks.primary, Color.White, 0.86f), c.primaryContainer)
        assertEquals(lerp(seahawks.secondary!!, Color.White, 0.86f), c.secondaryContainer)
        assertNotEquals(c.primaryContainer, c.secondaryContainer)
    }

    @Test
    fun containersFollowThemeInsteadOfBaseScheme() {
        val scheme = BuiltInKKCThemeTokens.copy(light = seahawks).toColorScheme(darkTheme = false)
        assertEquals(deriveContainers(seahawks, false).surfaceVariant, scheme.surfaceVariant)
        assertEquals(deriveContainers(seahawks, false).outlineVariant, scheme.outlineVariant)
    }

    @Test
    fun darkContainersAreDarkTintsOfTheThemeColors() {
        val dark = KKCThemePalette(
            primary = Color(0xFF79B2FF),
            secondary = Color(0xFF69BE28),
            background = Color.Black,
            surface = Color(0xFF162438)
        )
        val c = deriveContainers(dark, darkTheme = true)
        assertTrue(c.primaryContainer.luminance() < 0.25f)
        assertTrue(c.onPrimaryContainer.luminance() > 0.5f)
        assertEquals(lerp(dark.surface, dark.secondary!!, 0.26f), c.secondaryContainer)
    }
}
