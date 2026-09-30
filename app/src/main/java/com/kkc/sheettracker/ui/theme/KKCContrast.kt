package com.kkc.sheettracker.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * Minimum contrast of the dark scheme's primary against the dark surface. Many team themes reuse
 * their deep brand color (navy, maroon, black) as the dark-mode primary, which made every
 * primary-colored button, checkbox, icon and progress bar vanish on the dark surface.
 */
internal const val MIN_DARK_PRIMARY_CONTRAST = 4.5f

/** WCAG contrast ratio between two colors (1..21). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/**
 * Returns [primary] unchanged when it already reads on [surface]; otherwise raises its HSL
 * lightness (hue and saturation kept, so navy becomes a clear mid blue rather than gray) until it
 * reaches [MIN_DARK_PRIMARY_CONTRAST]. Near-black primaries with no hue lift toward light gray.
 */
internal fun readableDarkPrimary(primary: Color, surface: Color): Color {
    if (contrastRatio(primary, surface) >= MIN_DARK_PRIMARY_CONTRAST) return primary
    val (h, s, l) = toHsl(primary)
    var lightness = l
    while (lightness < 0.95f) {
        lightness += 0.01f
        val candidate = Color.hsl(h, s, lightness.coerceAtMost(1f), primary.alpha)
        if (contrastRatio(candidate, surface) >= MIN_DARK_PRIMARY_CONTRAST) return candidate
    }
    return Color.hsl(h, s, 0.95f, primary.alpha)
}

/** Text/icon color for content drawn on [background]: white or near-black, whichever reads better. */
internal fun contentColorFor(background: Color): Color {
    val dark = Color(0xFF0B0B0B)
    return if (contrastRatio(Color.White, background) >= contrastRatio(dark, background)) Color.White else dark
}

internal fun hueOf(color: Color): Float = toHsl(color).first

/** RGB -> HSL (hue in degrees 0..360, saturation and lightness 0..1). */
private fun toHsl(color: Color): Triple<Float, Float, Float> {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val l = (maxC + minC) / 2f
    val d = maxC - minC
    if (d == 0f) return Triple(0f, 0f, l)
    val s = d / (1f - kotlin.math.abs(2f * l - 1f))
    val h = when (maxC) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return Triple(if (h < 0f) h + 360f else h, s.coerceIn(0f, 1f), l)
}
