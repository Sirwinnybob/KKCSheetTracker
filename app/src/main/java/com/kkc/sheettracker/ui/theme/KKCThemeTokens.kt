package com.kkc.sheettracker.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class KKCThemeTokens(
    val id: String,
    val name: String,
    val light: KKCThemePalette,
    val dark: KKCThemePalette,
    val lightStatus: KKCStatusColors,
    val darkStatus: KKCStatusColors,
    val surface: KKCThemeSurfaceTokens,
    val header: KKCThemeHeaderTokens,
    val frosted: KKCThemeFrostedTokens,
    val shape: KKCThemeShapeTokens,
    val spacingScale: Float
) {
    fun palette(darkTheme: Boolean): KKCThemePalette = if (darkTheme) dark else light
    fun status(darkTheme: Boolean): KKCStatusColors = if (darkTheme) darkStatus else lightStatus
}

@Immutable
data class KKCThemePalette(
    val primary: Color,
    val background: Color,
    val surface: Color
)

@Immutable
data class KKCThemeSurfaceTokens(
    val cardAlpha: Float,
    val headerTintAlpha: Float
)

@Immutable
data class KKCThemeHeaderTokens(
    val backgroundPath: String?,
    val alpha: Float,
    val contentScale: KKCThemeHeaderContentScale
)

enum class KKCThemeHeaderContentScale {
    CROP,
    FIT,
    FILL_WIDTH
}

@Immutable
data class KKCThemeFrostedTokens(
    val backgroundAlpha: Float,
    val blurDp: Float
)

@Immutable
data class KKCThemeShapeTokens(
    val smallDp: Float,
    val mediumDp: Float,
    val largeDp: Float
)

val LocalKKCThemeTokens = staticCompositionLocalOf { BuiltInKKCThemeTokens }
val LocalKKCIsDarkTheme = staticCompositionLocalOf { false }

val BuiltInKKCThemeTokens = KKCThemeTokens(
    id = KKCThemeRepository.BUILT_IN_THEME_ID,
    name = "KKC Default",
    light = KKCThemePalette(
        primary = Color(0xFF1E5FAF),
        background = Color(0xFFF5F2EB),
        surface = Color.White
    ),
    dark = KKCThemePalette(
        primary = Color(0xFF79B2FF),
        background = Color.Black,
        surface = Color(0xFF162438)
    ),
    lightStatus = LightStatusColors,
    darkStatus = DarkStatusColors,
    surface = KKCThemeSurfaceTokens(
        cardAlpha = 1.0f,
        headerTintAlpha = 0.10f
    ),
    header = KKCThemeHeaderTokens(
        backgroundPath = null,
        alpha = 0.18f,
        contentScale = KKCThemeHeaderContentScale.CROP
    ),
    frosted = KKCThemeFrostedTokens(
        backgroundAlpha = 0.72f,
        blurDp = 14f
    ),
    shape = KKCThemeShapeTokens(
        smallDp = 6f,
        mediumDp = 9f,
        largeDp = 12f
    ),
    spacingScale = 1.0f
)
