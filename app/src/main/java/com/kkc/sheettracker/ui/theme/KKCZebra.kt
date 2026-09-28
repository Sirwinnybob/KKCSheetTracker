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

/**
 * Full-strength version of [kkcZebraTint]'s color choice for [rowIndex] (same primary/secondary
 * alternation, no alpha fade) -- used to make a single row (e.g. "the job you just opened") stand
 * out against the otherwise very faint zebra striping.
 *
 * Many team themes reuse the same deep brand color (navy, maroon, black) for both their light and
 * dark palette -- [readableDarkPrimary] lifts it in dark mode the same way [toColorScheme] already
 * does for `colorScheme.primary`, otherwise the "highlighted" row is barely different from the
 * dark surface it sits on.
 */
fun kkcZebraHighlight(palette: KKCThemePalette, darkTheme: Boolean, rowIndex: Int): Color {
    val odd = rowIndex % 2 != 0
    val secondary = palette.secondary
    val raw = when {
        secondary != null -> if (odd) secondary else palette.primary
        else -> palette.primary
    }
    return if (darkTheme) readableDarkPrimary(raw, palette.surface) else raw
}

@Composable
fun kkcZebraHighlight(rowIndex: Int): Color {
    val tokens = LocalKKCThemeTokens.current
    val dark = LocalKKCIsDarkTheme.current
    return kkcZebraHighlight(tokens.palette(dark), dark, rowIndex)
}
