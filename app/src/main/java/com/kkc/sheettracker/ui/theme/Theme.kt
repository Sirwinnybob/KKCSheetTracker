package com.kkc.sheettracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1E5FAF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF0B2B52),
    secondary = Color(0xFF3C6EA8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E6F7),
    onSecondaryContainer = Color(0xFF10243C),
    tertiary = Color(0xFF4F7D99),
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF122033),
    surface = Color.White,
    onSurface = Color(0xFF162236),
    surfaceVariant = Color(0xFFE2EDF7),
    onSurfaceVariant = Color(0xFF435467),
    outline = Color(0xFF7A95B0),
    outlineVariant = Color(0xFFC6D3E2),
    error = Color(0xFFC62828),
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF79B2FF),
    onPrimary = Color(0xFF0A2C54),
    primaryContainer = Color(0xFF123A67),
    onPrimaryContainer = Color(0xFFDCEBFF),
    secondary = Color(0xFF9BC3F3),
    onSecondary = Color(0xFF102943),
    secondaryContainer = Color(0xFF204668),
    onSecondaryContainer = Color(0xFFD8E8FA),
    tertiary = Color(0xFF9BC7D8),
    onTertiary = Color(0xFF113040),
    background = Color.Black,
    onBackground = Color(0xFFE8F0FA),
    surface = Color(0xFF162438),
    onSurface = Color(0xFFEBF2FC),
    surfaceVariant = Color(0xFF1E3047),
    onSurfaceVariant = Color(0xFFB4C6DA),
    outline = Color(0xFF4E6680),
    outlineVariant = Color(0xFF283D55),
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF330000)
)

/**
 * The scheme's pale container roles, derived from the theme's own colors instead of the fixed
 * blue in [LightColorScheme]/[DarkColorScheme]. Everything that uses `primaryContainer`,
 * `secondaryContainer`, `tertiaryContainer`, `surfaceVariant` or `outlineVariant` (chips, headers,
 * selected states, dividers) therefore follows the theme as a paler version of primary/secondary.
 */
internal data class KKCDerivedContainers(
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val surfaceVariant: Color,
    val outlineVariant: Color
)

internal fun deriveContainers(palette: KKCThemePalette, darkTheme: Boolean): KKCDerivedContainers {
    val primary = palette.primary
    val secondary = palette.secondary
    // The second hue: the theme's secondary, unless it is too pale (light mode) or too dark (dark
    // mode) to tint from -- e.g. the default theme's pale-blue secondary -- then primary again.
    val accent = when {
        secondary == null -> primary
        !darkTheme && secondary.luminance() > 0.7f -> primary
        darkTheme && secondary.luminance() < 0.12f -> primary
        else -> secondary
    }
    return if (!darkTheme) {
        fun pale(color: Color, whiteAmount: Float) = lerp(color, Color.White, whiteAmount)
        fun deep(color: Color) = lerp(color, Color.Black, 0.72f)
        KKCDerivedContainers(
            primaryContainer = pale(primary, 0.86f),
            onPrimaryContainer = deep(primary),
            secondaryContainer = pale(accent, 0.86f),
            onSecondaryContainer = deep(accent),
            tertiaryContainer = pale(accent, 0.80f),
            onTertiaryContainer = deep(accent),
            surfaceVariant = pale(primary, 0.90f),
            outlineVariant = pale(primary, 0.78f)
        )
    } else {
        val surface = palette.surface
        fun tinted(color: Color, amount: Float) = lerp(surface, color, amount)
        fun light(color: Color) = lerp(color, Color.White, 0.72f)
        KKCDerivedContainers(
            primaryContainer = tinted(primary, 0.26f),
            onPrimaryContainer = light(primary),
            secondaryContainer = tinted(accent, 0.26f),
            onSecondaryContainer = light(accent),
            tertiaryContainer = tinted(accent, 0.32f),
            onTertiaryContainer = light(accent),
            surfaceVariant = tinted(primary, 0.14f),
            outlineVariant = tinted(primary, 0.24f)
        )
    }
}

fun KKCThemeTokens.toColorScheme(darkTheme: Boolean): ColorScheme {
    val palette = palette(darkTheme)
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    val containers = deriveContainers(palette, darkTheme)
    // Dark mode only: lift a deep team primary so primary-colored controls stay readable. The
    // palette itself is untouched, so team-colored chrome (headers, bold mode) keeps the brand color.
    val primary = if (darkTheme) readableDarkPrimary(palette.primary, palette.surface) else palette.primary
    return base.copy(
        primary = primary,
        onPrimary = if (darkTheme) contentColorFor(primary) else base.onPrimary,
        background = palette.background,
        surface = palette.surface,
        primaryContainer = containers.primaryContainer,
        onPrimaryContainer = containers.onPrimaryContainer,
        secondaryContainer = containers.secondaryContainer,
        onSecondaryContainer = containers.onSecondaryContainer,
        tertiaryContainer = containers.tertiaryContainer,
        onTertiaryContainer = containers.onTertiaryContainer,
        surfaceVariant = containers.surfaceVariant,
        outlineVariant = containers.outlineVariant
    )
}

fun KKCThemeTokens.toShapes(): Shapes {
    return Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(shape.smallDp.dp),
        medium = RoundedCornerShape(shape.mediumDp.dp),
        large = RoundedCornerShape(shape.largeDp.dp),
        extraLarge = RoundedCornerShape(21.dp)
    )
}

val KKCShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(9.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(21.dp)
)

object KKCShapeTokens {
    val pill                    = RoundedCornerShape(999.dp)
    val statusBorderWidth       = 3.dp
    val splitHandleBarThickness = 5.dp
    val splitDividerThickness   = 1.dp
}


@Composable
fun KKCTheme(
    darkTheme: Boolean = false,
    themeTokens: KKCThemeTokens = BuiltInKKCThemeTokens,
    content: @Composable () -> Unit
) {
    val colorScheme = themeTokens.toColorScheme(darkTheme)
    val statusColors = themeTokens.status(darkTheme)
    val shapes = themeTokens.toShapes()

    CompositionLocalProvider(
        LocalKKCStatusColors provides statusColors,
        LocalKKCThemeTokens provides themeTokens,
        LocalKKCIsDarkTheme provides darkTheme
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KKCTypography,
            shapes = shapes,
            content = content
        )
    }
}
