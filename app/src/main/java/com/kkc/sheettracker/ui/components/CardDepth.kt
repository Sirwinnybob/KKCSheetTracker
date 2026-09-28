package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme

private val liftShadowColor = Color(0xFF0F1B2D).copy(alpha = 0.6f)

/**
 * Drop shadow + clip shared by the list, grid and thumbnail job cards.
 *
 * A white card on a white light-mode background reads flat with the stock 4dp shadow, so when
 * [lifted] is set (grid/thumbnail) light mode gets a deeper slate-tinted shadow plus a hairline
 * edge. Dark mode and the list view keep the stock 4dp shadow. Low-end devices skip the shadow
 * and use the hairline edge alone.
 *
 * Deliberately not `Surface(shadowElevation)` / a translucent fill — see CLAUDE.md on shadow bleed.
 */
@Composable
fun Modifier.kkcCardDepth(
    shape: Shape,
    lifted: Boolean = false,
    shadowsDisabled: Boolean = false
): Modifier {
    val liftedLight = lifted && !LocalKKCIsDarkTheme.current
    val shadowed = when {
        shadowsDisabled -> this
        liftedLight -> shadow(
            elevation = 10.dp,
            shape = shape,
            clip = false,
            ambientColor = liftShadowColor,
            spotColor = liftShadowColor
        )
        else -> shadow(4.dp, shape, clip = false)
    }
    val clipped = shadowed.clip(shape)
    return if (shadowsDisabled || liftedLight) {
        clipped.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    } else {
        clipped
    }
}
