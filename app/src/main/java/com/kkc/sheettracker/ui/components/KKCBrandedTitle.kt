package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import com.kkc.sheettracker.ui.theme.KKCThemeHeaderTokens
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
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
 * text) when one is set, appending " - $modeSuffix" either way. Used by both the Dashboard and
 * Jobs screen top bars so the two don't duplicate this priority logic.
 */
@Composable
fun KKCBrandedTitle(modeSuffix: String, modifier: Modifier = Modifier) {
    val header = LocalKKCThemeTokens.current.header
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
                Text(
                    text = header.badgeText!!.uppercase(),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            BrandedTitleKind.DEFAULT -> {
                Text("KKC Dashboard", style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(" - $modeSuffix", style = MaterialTheme.typography.titleMedium)
    }
}
