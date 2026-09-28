package com.kkc.sheettracker.ui.components

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme

private val liftShadowColor = Color(0xFF0F1B2D).copy(alpha = 0.6f)

/**
 * Drop shadow + clip for opaque cards: job cards, board cards and columns, dashboard and settings
 * cards. Apply the card's opaque fill after it.
 *
 * Every card gets an [elevation] shadow (4dp by default) in both themes. A white card on a white
 * light-mode background still reads flat, so when [lifted] is set (grid and thumbnail job cards)
 * light mode instead gets a deeper slate-tinted shadow plus a hairline edge. Where low-end mode
 * turns shadows off, cards get the hairline edge alone.
 *
 * Deliberately not `Surface(shadowElevation)` / a translucent fill — see CLAUDE.md on shadow bleed.
 */
@Composable
fun Modifier.kkcCardDepth(
    shape: Shape,
    lifted: Boolean = false,
    elevation: Dp = 4.dp
): Modifier {
    val shadowsDisabled = LocalLowEndMode.current.shadowsDisabled
    val liftedLight = lifted && !LocalKKCIsDarkTheme.current
    val shadowed = when {
        shadowsDisabled -> this
        // Android 8.x ignores shadow colors, so the slate tint would draw as a much darker black
        // 10dp shadow there; a shallower plain one comes closer to the tinted look.
        liftedLight && Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> shadow(6.dp, shape, clip = false)
        liftedLight -> shadow(
            elevation = 10.dp,
            shape = shape,
            clip = false,
            ambientColor = liftShadowColor,
            spotColor = liftShadowColor
        )
        else -> shadow(elevation, shape, clip = false)
    }
    val clipped = shadowed.clip(shape)
    return if (shadowsDisabled || liftedLight) {
        clipped.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    } else {
        clipped
    }
}
