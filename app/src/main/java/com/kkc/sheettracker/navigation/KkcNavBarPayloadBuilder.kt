package com.kkc.sheettracker.navigation

import androidx.compose.material3.BadgeDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.components.NavDestination
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import com.kkc.sheettracker.ui.theme.boldGradientColors
import com.kkc.sheettracker.ui.theme.kkcFrostedBaseColor
import com.kkc.sheettracker.ui.theme.kkcFrostedContentColor

// KEEP IN SYNC — reads the same values AppScaffold.kt MorphingNavBar/MorphingNavIconRow read.
// If the navbar starts reading a new theme value, add it here, to KkcNavBarContract (both repos)
// and to Hours Tracker kkcnav/KkcNavBar.kt.
/**
 * Resolves the bottom navbar's current look from the live KKC theme, exactly as
 * AppScaffold.kt MorphingNavBar / MorphingNavIconRow read it, so Hours Tracker never has to parse
 * KKC theme JSON. Must be called inside KKC's theme composition.
 */
@Composable
internal fun currentKkcNavBarPayload(
    destinations: List<NavDestination>,
    supplyCount: Int,
    safetyCount: Int
): KkcNavBarPayload {
    val tokens = LocalKKCThemeTokens.current
    val dark = LocalKKCIsDarkTheme.current
    val lowEnd = LocalLowEndMode.current
    val scheme = MaterialTheme.colorScheme
    val badgeContainer = BadgeDefaults.containerColor
    return KkcNavBarPayload(
        destinations = destinations.map { it.route },
        supplyCount = supplyCount,
        safetyCount = safetyCount,
        dark = dark,
        primary = scheme.primary.toArgb(),
        onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
        surfaceVariant = scheme.surfaceVariant.toArgb(),
        frostedBase = kkcFrostedBaseColor().toArgb(),
        frostedContent = kkcFrostedContentColor().toArgb(),
        frostedAlpha = tokens.frosted.backgroundAlpha,
        frostedBlurDp = tokens.frosted.blurDp,
        boldMode = tokens.boldMode,
        boldGradient = boldGradientColors(tokens.palette(dark)).map { it.toArgb() },
        badgeContainer = badgeContainer.toArgb(),
        badgeContent = contentColorFor(badgeContainer).toArgb(),
        indicatorCornerDp = tokens.shape.mediumDp,
        animDisabled = lowEnd.animationsDisabled,
        blurDisabled = lowEnd.blurDisabled,
        // Same condition MorphingNavBar uses for its 0.dp shadow.
        shadowsDisabled = lowEnd.shadowsDisabled || lowEnd.webViewBlurSuppressed
    )
}
