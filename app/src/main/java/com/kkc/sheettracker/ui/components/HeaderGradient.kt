package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import java.io.File

/**
 * Very slight blue wash used as the background of every screen's [androidx.compose.material3.TopAppBar]
 * so headers read as one cohesive, lightly branded surface across the app.
 *
 * Theme-aware: tints the surface color a touch toward [primary][androidx.compose.material3.ColorScheme.primary]
 * at the leading edge and fades back to plain surface — subtle, not a band of color.
 *
 * Usage: set the bar's `containerColor = Color.Transparent` and apply
 * `modifier = Modifier.headerBackground()` so the theme header shows through.
 */
@Composable
fun headerGradientBrush(): Brush {
    val tokens = LocalKKCThemeTokens.current
    val surface = MaterialTheme.colorScheme.surface
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = tokens.surface.headerTintAlpha).compositeOver(surface)
    return Brush.horizontalGradient(
        listOf(tint, surface, tint)
    )
}

/**
 * Shared top-app-bar background. Themes may provide a synced SVG header image; when they do,
 * it is painted over the normal surface at low alpha. Missing/invalid artwork falls back to
 * the original subtle gradient.
 */
@Composable
fun Modifier.headerBackground(): Modifier {
    val tokens = LocalKKCThemeTokens.current
    val backgroundPath = tokens.header.backgroundPath
    if (backgroundPath.isNullOrBlank()) {
        return background(headerGradientBrush())
    }

    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(File(backgroundPath))
            .crossfade(false)
            .build(),
        imageLoader = imageLoader
    )

    return background(MaterialTheme.colorScheme.surface)
        .clipToBounds()
        .drawWithContent {
            val alpha = tokens.header.alpha.coerceIn(0f, 1f)
            with(painter) {
                draw(size = size, alpha = alpha)
            }
            drawContent()
        }
}
