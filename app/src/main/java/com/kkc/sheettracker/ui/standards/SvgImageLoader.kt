package com.kkc.sheettracker.ui.standards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.decode.SvgDecoder

/**
 * Molding profile art ships as .svg. Coil's default ImageLoader has no SVG decoder registered
 * app-wide, so — matching the pattern already used for HeaderGradient (SVG) and
 * TimeclockBackground (GIF) — every screen in this package that renders profile art builds one
 * locally via this helper and passes it explicitly to AsyncImage.
 */
@Composable
internal fun rememberSvgImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
}
