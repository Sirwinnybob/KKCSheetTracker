package com.kkc.sheettracker.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

const val TIMECARD_DESIGN_WIDTH_DP: Int = 1054

/**
 * Overrides LocalDensity so [designWidthDp] equals the screen's actual width on every device.
 * Same .dp / .sp values render at the same fraction of screen width regardless of what the
 * vendor reports for densityDpi (Samsung tablets sometimes round inconsistently). User
 * fontScale is preserved so accessibility scaling still applies.
 */
@Composable
fun FixedDensityWrapper(
    designWidthDp: Int = TIMECARD_DESIGN_WIDTH_DP,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val current = LocalDensity.current
    val referenceDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val referencePx = referenceDp * current.density
    val scaledDensity = referencePx / designWidthDp
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = scaledDensity,
            fontScale = current.fontScale
        )
    ) { content() }
}
