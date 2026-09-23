package com.kkc.sheettracker.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Very faint theme tint for alternating ("zebra") list rows, kept well under 10% alpha so it
 * reads as a hint, not a stripe. Themes with a secondary color alternate primary / secondary
 * (e.g. Seahawks blue / green) so both row colors are themed; themes without one tint odd rows
 * with primary and leave even rows plain.
 */
fun kkcZebraTint(palette: KKCThemePalette, darkTheme: Boolean, rowIndex: Int): Color {
    val alpha = if (darkTheme) 0.07f else 0.05f
    val odd = rowIndex % 2 != 0
    val secondary = palette.secondary
    return when {
        secondary != null -> (if (odd) secondary else palette.primary).copy(alpha = alpha)
        odd -> palette.primary.copy(alpha = alpha)
        else -> Color.Transparent
    }
}

@Composable
fun kkcZebraTint(rowIndex: Int): Color {
    val tokens = LocalKKCThemeTokens.current
    val dark = LocalKKCIsDarkTheme.current
    return kkcZebraTint(tokens.palette(dark), dark, rowIndex)
}
