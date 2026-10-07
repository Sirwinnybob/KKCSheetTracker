package com.kkc.sheettracker.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.components.NavDestination
import com.kkc.sheettracker.ui.theme.KKCThemeTokens
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import com.kkc.sheettracker.ui.theme.boldGradientColors
import com.kkc.sheettracker.ui.theme.kkcFrostedBaseColor
import com.kkc.sheettracker.ui.theme.kkcFrostedContentColor
import com.kkc.sheettracker.ui.theme.toColorScheme

// KEEP IN SYNC — reads the same values AppScaffold.kt MorphingNavBar/MorphingNavIconRow read.
// If the navbar starts reading a new theme value, add it here (for BOTH light and dark), to
// KkcNavBarContract (both repos) and to Hours Tracker kkcnav/KkcNavBar.kt.
// Contract v2: KKC sends BOTH light and dark color sets; the bar follows Hours Tracker's own
// light/dark mode, so the KKC mode currently on screen is irrelevant to the payload.
// Deliberate divergence: blurDisabled / shadowsDisabled carry the user's low-end settings only,
// NOT LowEndModeFlags.webViewBlurSuppressed (KKC-only, transient; HT has no WebView).
/**
 * Resolves the bottom navbar's look from the live KKC theme tokens (which already reflect a theme
 * override / synced theme), exactly as AppScaffold.kt MorphingNavBar / MorphingNavIconRow read it,
 * so Hours Tracker never has to parse KKC theme JSON. Must be called inside KKC's theme composition.
 */
@Composable
internal fun currentKkcNavBarPayload(
    destinations: List<NavDestination>,
    supplyCount: Int,
    safetyCount: Int
): KkcNavBarPayload {
    val tokens = LocalKKCThemeTokens.current
    val userFlags = LocalLowEndMode.current.copy(webViewBlurSuppressed = false)
    return KkcNavBarPayload(
        destinations = destinations.map { it.route },
        supplyCount = supplyCount,
        safetyCount = safetyCount,
        light = tokens.navBarColors(darkTheme = false),
        dark = tokens.navBarColors(darkTheme = true),
        frostedAlpha = tokens.frosted.backgroundAlpha,
        frostedBlurDp = tokens.frosted.blurDp,
        boldMode = tokens.boldMode,
        indicatorCornerDp = tokens.shape.mediumDp,
        animDisabled = userFlags.animationsDisabled,
        // User settings only. KKC's own bar also folds in webViewBlurSuppressed (the transient
        // "3D pane on screen" flag: MorphingNavBar's 0.dp shadow, blurDisabled), but Hours Tracker
        // has no WebView, so mirroring it would leave HT with a flat bar for the whole session
        // after a launch from the 3D viewer.
        blurDisabled = userFlags.blurDisabled,
        shadowsDisabled = userFlags.shadowsDisabled
    )
}

/**
 * The color set for one mode. `toColorScheme(darkTheme)` reproduces exactly what KKCTheme builds for
 * that mode from these tokens. Badge colors are what M3 `BadgeDefaults.containerColor` (error) and
 * `contentColorFor(error)` (onError) resolve to.
 */
private fun KKCThemeTokens.navBarColors(darkTheme: Boolean): KkcNavBarColors {
    val scheme = toColorScheme(darkTheme)
    return KkcNavBarColors(
        primary = scheme.primary.toArgb(),
        onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
        surfaceVariant = scheme.surfaceVariant.toArgb(),
        frostedBase = kkcFrostedBaseColor(this, scheme, darkTheme).toArgb(),
        frostedContent = kkcFrostedContentColor(this, scheme, darkTheme).toArgb(),
        boldGradient = boldGradientColors(palette(darkTheme)).map { it.toArgb() },
        badgeContainer = scheme.error.toArgb(),
        badgeContent = scheme.onError.toArgb()
    )
}
