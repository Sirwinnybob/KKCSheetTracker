package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import com.kkc.sheettracker.ui.theme.KKCThemeHeaderTokens
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import com.kkc.sheettracker.ui.theme.boldChipTextColor
import com.kkc.sheettracker.ui.theme.boldGradientBrush
import java.io.File

internal enum class BrandedTitleKind { LOGO, TEXT, DEFAULT }

/**
 * Priority when a theme sets both fields: an explicit logo image wins over styled text,
 * which wins over the literal "KKC Dashboard" default every theme had before badges existed.
 */
internal fun resolveBrandedTitleKind(header: KKCThemeHeaderTokens): BrandedTitleKind = when {
    !header.badgeLogoPath.isNullOrBlank() -> BrandedTitleKind.LOGO
    !header.badgeText.isNullOrBlank() -> BrandedTitleKind.TEXT
    else -> BrandedTitleKind.DEFAULT
}

/**
 * Replaces the literal "KKC Dashboard" title text with a theme's badge (logo image or styled
 * text) when one is set, appending " - $modeSuffix" either way. Intended for use by both the
 * Dashboard and Jobs screen top bars (wired in a later task) so the two don't duplicate this
 * priority logic. When the active theme's `boldMode` is on, styled text renders on a gradient
 * chip instead of plain colored text on the app bar background.
 */
@Composable
fun KKCBrandedTitle(modeSuffix: String, modifier: Modifier = Modifier) {
    val tokens = LocalKKCThemeTokens.current
    val header = tokens.header
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        when (resolveBrandedTitleKind(header)) {
            BrandedTitleKind.LOGO -> {
                val context = LocalContext.current
                val imageLoader = remember(context) {
                    ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build()
                }
                AsyncImage(
                    model = File(header.badgeLogoPath!!),
                    contentDescription = "Team logo",
                    imageLoader = imageLoader,
                    modifier = Modifier.height(28.dp)
                )
            }
            BrandedTitleKind.TEXT -> {
                if (tokens.boldMode) {
                    val palette = tokens.palette(LocalKKCIsDarkTheme.current)
                    val textColor = boldChipTextColor(palette)
                    val shadowColor = if (textColor == Color.White) {
                        Color.Black.copy(alpha = 0.4f)
                    } else {
                        Color.White.copy(alpha = 0.4f)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(boldGradientBrush(palette))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = header.badgeText!!.uppercase(),
                            color = textColor,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium.copy(
                                shadow = Shadow(
                                    color = shadowColor,
                                    offset = Offset(0f, 1f),
                                    blurRadius = 3f
                                )
                            )
                        )
                    }
                } else {
                    Text(
                        text = header.badgeText!!.uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            BrandedTitleKind.DEFAULT -> {
                Text("KKC Dashboard", style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(" - $modeSuffix", style = MaterialTheme.typography.titleMedium)
    }
}
