package com.kkc.sheettracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The two stops a bold-mode gradient blends between: a theme's primary and, when set, secondary
 * color. Falls back to primary alone (a flat "gradient") when no secondary color is configured,
 * so callers never need a separate no-secondary code path.
 */
fun boldGradientColors(palette: KKCThemePalette): List<Color> {
    val end = palette.secondary ?: palette.primary
    return listOf(palette.primary, end)
}

/** A `Brush` built from [boldGradientColors] — the actual fill used by bold-mode chrome. */
fun boldGradientBrush(palette: KKCThemePalette): Brush = Brush.linearGradient(boldGradientColors(palette))

/**
 * A single blended tint for frosted-glass "glow" surfaces (navbar, Timeclock, Calculator): the
 * midpoint between primary and secondary, or plain primary when no secondary is set, at the
 * given alpha.
 */
fun boldGlowColor(palette: KKCThemePalette, alpha: Float): Color {
    val base = palette.secondary?.let { lerp(palette.primary, it, 0.5f) } ?: palette.primary
    return base.copy(alpha = alpha)
}

/**
 * The base color every frosted-glass surface in the app builds its tint from. Returns the
 * bold-mode glow (full alpha — callers apply their own `.copy(alpha = ...)` exactly as they do
 * today) when the active theme has `boldMode` on, otherwise the same neutral
 * `MaterialTheme.colorScheme.surface` every frosted surface already uses. This is a drop-in
 * replacement for that one expression, not a new modifier chain — see its call sites in
 * `AppScaffold.kt`, `TimecardScreen.kt`, and `CalculatorOverlay.kt`.
 */
@Composable
fun kkcFrostedBaseColor(): Color {
    val tokens = LocalKKCThemeTokens.current
    return if (tokens.boldMode) {
        boldGlowColor(tokens.palette(LocalKKCIsDarkTheme.current), alpha = 1f)
    } else {
        MaterialTheme.colorScheme.surface
    }
}
