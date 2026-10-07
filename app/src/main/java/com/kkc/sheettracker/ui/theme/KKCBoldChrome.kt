package com.kkc.sheettracker.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * The two stops a bold-mode gradient blends between: a theme's primary and, when set, secondary
 * color. Falls back to primary alone (a flat "gradient") when no secondary color is configured,
 * so callers never need a separate no-secondary code path.
 */
fun boldGradientColors(palette: KKCThemePalette): List<Color> {
    val end = palette.secondary ?: palette.primary
    return listOf(palette.primary, end)
}

// KEEP IN SYNC — Hours Tracker's mirrored navbar rebuilds this brush as `Brush.linearGradient(stops)`
// from the payload stops in C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\kkcnav\KkcNavBar.kt.
// If this brush changes (gradient type, direction, stops), check that mirror. See CLAUDE.md
// "KKC navbar mirror (Hours Tracker)".
/** A `Brush` built from [boldGradientColors] — the actual fill used by bold-mode chrome. */
fun boldGradientBrush(palette: KKCThemePalette): Brush = Brush.linearGradient(boldGradientColors(palette))

/**
 * Legible text color for content placed on top of a bold-mode gradient: black when [KKCThemePalette.primary]
 * is light, white when it's dark. Anchored on `primary` alone (not blended with `secondary`) because
 * `boldGradientBrush` always starts its gradient at `primary` in the corner where this text is
 * positioned (header badge, Dashboard hero card title/value) — averaging in `secondary`'s luminance
 * previously picked white text for teams with a light `primary` and a dark `secondary` (e.g. Steelers'
 * gold `primary` with a black `secondary`), which is unreadable at the corner the text actually sits on.
 */
fun boldChipTextColor(palette: KKCThemePalette): Color {
    return if (palette.primary.luminance() > 0.5) Color.Black else Color.White
}

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
fun kkcFrostedBaseColor(): Color =
    kkcFrostedBaseColor(LocalKKCThemeTokens.current, MaterialTheme.colorScheme, LocalKKCIsDarkTheme.current)

/**
 * Non-composable form of [kkcFrostedBaseColor] so the Hours Tracker navbar payload builder can
 * resolve BOTH the light and dark sets (it passes `tokens.toColorScheme(dark)`). The composable
 * version above delegates here, so the logic lives in one place.
 */
fun kkcFrostedBaseColor(tokens: KKCThemeTokens, scheme: ColorScheme, darkTheme: Boolean): Color =
    if (tokens.boldMode) {
        boldGlowColor(tokens.palette(darkTheme), alpha = 1f)
    } else {
        scheme.surface
    }

// KEEP IN SYNC — Hours Tracker's mirrored navbar does not recompute this: it receives the resolved
// color via KkcNavBarPayload.light/dark.frostedContent (KKC computes BOTH modes with the
// non-composable overloads below; see currentKkcNavBarPayload in KkcNavBarPayloadBuilder.kt and
// C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\kkcnav\KkcNavBar.kt).
// If the frosted base/alpha logic changes, check the mirror. See CLAUDE.md "KKC navbar mirror (Hours Tracker)".
/**
 * Legible content color for text/icons sitting on a frosted surface tinted by [kkcFrostedBaseColor].
 * Outside bold mode the surface is the neutral `colorScheme.surface`, so `onSurface` is correct.
 * In bold mode the glass is tinted with the theme glow (often a mid-luminance brand color) and
 * `onSurface`/`onSurfaceVariant`/`primary` can all land near the tint, so pick black or white
 * from the glow composited over the app background at the frosted alpha — what the eye actually sees.
 */
@Composable
fun kkcFrostedContentColor(): Color =
    kkcFrostedContentColor(LocalKKCThemeTokens.current, MaterialTheme.colorScheme, LocalKKCIsDarkTheme.current)

/**
 * Non-composable form of [kkcFrostedContentColor] (see [kkcFrostedBaseColor] overload): resolves the
 * color for an explicit [tokens] / [scheme] / [darkTheme] triple so both modes can be computed at once.
 */
fun kkcFrostedContentColor(tokens: KKCThemeTokens, scheme: ColorScheme, darkTheme: Boolean): Color {
    if (!tokens.boldMode) return scheme.onSurface
    val alpha = tokens.frosted.backgroundAlpha.coerceIn(0.5f, 0.95f)
    val effective = kkcFrostedBaseColor(tokens, scheme, darkTheme).copy(alpha = alpha).compositeOver(scheme.background)
    // 0.179 is where black and white give equal WCAG contrast; above it black wins.
    return if (effective.luminance() > 0.179f) Color.Black else Color.White
}
