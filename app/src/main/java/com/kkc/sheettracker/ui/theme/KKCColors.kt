package com.kkc.sheettracker.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class KKCStatusColors(
    val complete: Color,
    val bad: Color,
    val skip: Color,
    val inProgress: Color,
    val notStarted: Color,
    val completeBg: Color,
    val badBg: Color,
    val skipBg: Color,
    val completeBgRow: Color,
    val skipBgRow: Color,
    val inProgressBorder: Color,
    val completeBorder: Color,
    val skipBorder: Color,
    val widthBandPalette: List<Color>,
    val progressGradientStart: Color,
    val progressGradientEnd: Color
)

val LightStatusColors = KKCStatusColors(
    complete = Color(0xFF388E3C),
    bad = Color(0xFFC62828),
    skip = Color(0xFFE65100),
    inProgress = Color(0xFF1565C0),
    notStarted = Color(0xFF78909C),
    completeBg = Color(0xFF4CAF50),
    badBg = Color(0xFFEF5350),
    skipBg = Color(0xFFFFA726),
    completeBgRow = Color(0x14388E3C),
    skipBgRow = Color(0x1AE65100),
    inProgressBorder = Color(0xFF1565C0),
    completeBorder = Color(0xFF388E3C),
    skipBorder = Color(0xFFE65100),
    widthBandPalette = listOf(
        Color(0xFFF7E56A),
        Color(0xFF97E17F),
        Color(0xFF76D9F7),
        Color(0xFFF7A3D1),
        Color(0xFFFFC885)
    ),
    progressGradientStart = Color(0x332A78D1),
    progressGradientEnd = Color(0x1A2A78D1)
)

val DarkStatusColors = KKCStatusColors(
    complete = Color(0xFF66BB6A),
    bad = Color(0xFFFF6B6B),
    skip = Color(0xFFFFB74D),
    inProgress = Color(0xFF64B5F6),
    notStarted = Color(0xFF90A4AE),
    completeBg = Color(0xFF66BB6A),
    badBg = Color(0xFFFF6B6B),
    skipBg = Color(0xFFFFB74D),
    completeBgRow = Color(0x1466BB6A),
    skipBgRow = Color(0x1AFFB74D),
    inProgressBorder = Color(0xFF64B5F6),
    completeBorder = Color(0xFF66BB6A),
    skipBorder = Color(0xFFFFB74D),
    widthBandPalette = listOf(
        Color(0xFFB7A83F),
        Color(0xFF5AA149),
        Color(0xFF4FA7C0),
        Color(0xFFB9789D),
        Color(0xFFC28A54)
    ),
    progressGradientStart = Color(0x3379B2FF),
    progressGradientEnd = Color(0x1A79B2FF)
)

val LocalKKCStatusColors = staticCompositionLocalOf { LightStatusColors }

object KKCThemeColors {
    val statusColors: KKCStatusColors
        @Composable
        get() = LocalKKCStatusColors.current
}

object KKCAlpha {
    const val handleBar                = 0.6f
    const val outlineTrack             = 0.3f
    const val dividerSubtle            = 0.5f
    const val containerDimmed          = 0.55f
    const val inProgressTint           = 0.08f
    const val badPartsTint             = 0.12f
    const val statusBadgeBg            = 0.12f
    const val primaryContainerOverview = 0.4f
    const val gradientAccentTop = 0.06f   // gradient overlay alpha at top of dashboards
    const val cardHeroTint      = 0.35f   // overview card primaryContainer tint in dark mode
    const val lightCardHeroTint = 0.60f   // overview card primaryContainer tint in light mode
}

