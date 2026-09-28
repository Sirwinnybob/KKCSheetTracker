package com.kkc.sheettracker.ui.components

import androidx.compose.ui.graphics.Color
import com.kkc.sheettracker.ui.theme.KKCThemeHeaderContentScale
import com.kkc.sheettracker.ui.theme.KKCThemeHeaderTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class KKCBrandedTitleTest {

    private fun header(badgeText: String? = null, badgeLogoPath: String? = null) = KKCThemeHeaderTokens(
        backgroundPath = null,
        alpha = 0.18f,
        contentScale = KKCThemeHeaderContentScale.CROP,
        badgeText = badgeText,
        badgeLogoPath = badgeLogoPath
    )

    @Test
    fun logoPathTakesPriorityOverText() {
        assertEquals(
            BrandedTitleKind.LOGO,
            resolveBrandedTitleKind(header(badgeText = "CHIEFS", badgeLogoPath = "/x/logo.svg"))
        )
    }

    @Test
    fun textIsUsedWhenNoLogoPath() {
        assertEquals(BrandedTitleKind.TEXT, resolveBrandedTitleKind(header(badgeText = "CHIEFS")))
    }

    @Test
    fun defaultWhenNeitherIsSet() {
        assertEquals(BrandedTitleKind.DEFAULT, resolveBrandedTitleKind(header()))
    }

    @Test
    fun blankBadgeTextTreatedAsAbsent() {
        assertEquals(BrandedTitleKind.DEFAULT, resolveBrandedTitleKind(header(badgeText = "   ")))
    }

    @Test
    fun blankBadgeLogoPathTreatedAsAbsent() {
        assertEquals(BrandedTitleKind.TEXT, resolveBrandedTitleKind(header(badgeText = "CHIEFS", badgeLogoPath = "  ")))
    }

    @Test
    fun defaultLogoIsBlackOnLightHeader() {
        assertEquals(Color.Black, defaultLogoTint(Color(0xFFF5F7FA)))
    }

    @Test
    fun defaultLogoIsWhiteOnDarkHeader() {
        assertEquals(Color.White, defaultLogoTint(Color(0xFF1A1C1E)))
    }

    @Test
    fun defaultLogoIsWhiteOnSaturatedDarkHeader() {
        assertEquals(Color.White, defaultLogoTint(Color(0xFF0B3D91)))
    }
}
