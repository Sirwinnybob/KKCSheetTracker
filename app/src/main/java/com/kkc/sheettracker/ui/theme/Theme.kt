package com.kkc.sheettracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
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
    background = Color(0xFFF5F2EB),
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

fun KKCThemeTokens.toColorScheme(darkTheme: Boolean): ColorScheme {
    val palette = palette(darkTheme)
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    return base.copy(
        primary = palette.primary,
        background = palette.background,
        surface = palette.surface
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
