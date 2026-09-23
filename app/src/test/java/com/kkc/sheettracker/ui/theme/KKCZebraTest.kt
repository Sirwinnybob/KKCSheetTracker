package com.kkc.sheettracker.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KKCZebraTest {
    private val twoTone = KKCThemePalette(
        primary = Color(0xFF002244),
        secondary = Color(0xFF69BE28),
        background = Color.White,
        surface = Color.White
    )
    private val oneTone = twoTone.copy(secondary = null)

    @Test
    fun secondaryThemesAlternatePrimaryAndSecondary() {
        assertEquals(twoTone.primary.copy(alpha = 0.05f), kkcZebraTint(twoTone, darkTheme = false, rowIndex = 0))
        assertEquals(twoTone.secondary!!.copy(alpha = 0.05f), kkcZebraTint(twoTone, darkTheme = false, rowIndex = 1))
    }

    @Test
    fun singleColorThemesTintOddRowsOnly() {
        assertEquals(Color.Transparent, kkcZebraTint(oneTone, darkTheme = false, rowIndex = 0))
        assertEquals(oneTone.primary.copy(alpha = 0.05f), kkcZebraTint(oneTone, darkTheme = false, rowIndex = 1))
    }

    @Test
    fun darkThemeIsSlightlyStrongerButStillFaint() {
        val tint = kkcZebraTint(twoTone, darkTheme = true, rowIndex = 1)
        assertEquals(0.07f, tint.alpha, 0.005f)
        assertTrue(tint.alpha < 0.1f)
    }
}
