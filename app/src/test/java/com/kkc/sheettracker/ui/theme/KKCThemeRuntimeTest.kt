package com.kkc.sheettracker.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class KKCThemeRuntimeTest {

    @Test
    fun builtInLightColorSchemeMatchesExistingDefaultColors() {
        val scheme = BuiltInKKCThemeTokens.toColorScheme(darkTheme = false)

        assertEquals(Color(0xFF1E5FAF), scheme.primary)
        assertEquals(Color(0xFFEFF4FA), scheme.background)
        assertEquals(Color.White, scheme.surface)
    }

    @Test
    fun customTokensOverrideMaterialColorSchemeBasics() {
        val custom = BuiltInKKCThemeTokens.copy(
            light = KKCThemePalette(
                primary = Color(0xFF005500),
                background = Color(0xFFEAF6EA),
                surface = Color(0xFFF9FFF9)
            )
        )

        val scheme = custom.toColorScheme(darkTheme = false)

        assertEquals(Color(0xFF005500), scheme.primary)
        assertEquals(Color(0xFFEAF6EA), scheme.background)
        assertEquals(Color(0xFFF9FFF9), scheme.surface)
    }
}
