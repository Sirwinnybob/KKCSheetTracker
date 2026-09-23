package com.kkc.sheettracker.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import com.kkc.sheettracker.ui.theme.KKCStatusColors
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.theme.KKCThemePalette
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import com.kkc.sheettracker.ui.theme.boldChipTextColor
import com.kkc.sheettracker.ui.theme.boldGradientBrush

/** Which theme color family a sliding pill (and its matching tab labels) draws from. */
enum class KKCPillAccent {
    /** The theme's brand color — bold-mode gradient, or a primary tint otherwise. */
    PRIMARY,

    /** Attention state (e.g. the hardwoods CHANGED tab) — themed status "skip" color. */
    ATTENTION
}

/** Resolved colors for one sliding-pill state. Build with [kkcPillStyle] / [rememberKKCPillStyle]. */
@Immutable
data class KKCPillStyle(
    /** Track behind the pill (the segmented control's own background). */
    val container: Color,
    val containerBorder: Color,
    /** True when [container] is a theme color rather than the plain surface — bare strips then need their own track. */
    val filledContainer: Boolean,
    val fill: Brush,
    /** Solid stand-in for [fill] for surfaces that can't take a Brush (e.g. the 3D web viewer). */
    val fillColor: Color,
    val border: Color,
    /** Label color while the pill sits under the label. */
    val selectedText: Color,
    /** Label color while the label is not selected. */
    val unselectedText: Color
)

/** Black or white, whichever has the higher WCAG contrast on [color] (crossover at relative luminance ~0.18). */
private fun contrastOn(color: Color): Color = if (color.luminance() > 0.179f) Color.Black else Color.White

/**
 * Pure color resolution for the sliding pill so it stays unit-testable.
 *
 * Themes with a secondary color use two solid theme colors: **primary is the track, secondary is
 * the sliding pill**, with black/white labels chosen for contrast on each. Single-color themes keep
 * the neutral surface track with a translucent primary pill (selected text stays `onSurface` so it
 * remains legible on light-primary themes).
 */
fun kkcPillStyle(
    palette: KKCThemePalette,
    boldMode: Boolean,
    status: KKCStatusColors,
    onSurface: Color,
    surface: Color,
    outlineVariant: Color,
    accent: KKCPillAccent = KKCPillAccent.PRIMARY
): KKCPillStyle {
    val secondary = palette.secondary
    if (secondary != null) {
        val pillColor = if (accent == KKCPillAccent.ATTENTION) status.skip else secondary
        return KKCPillStyle(
            container = palette.primary,
            // Same color as the fill: solid two-color layouts read cleaner with no outline ring
            // (a translucent light/dark outline showed up as a stray halo on the edges).
            containerBorder = palette.primary,
            filledContainer = true,
            fill = SolidColor(pillColor),
            fillColor = pillColor,
            border = pillColor,
            selectedText = contrastOn(pillColor),
            unselectedText = contrastOn(palette.primary)
        )
    }
    val container = surface
    val containerBorder = outlineVariant.copy(alpha = 0.75f)
    return when (accent) {
        KKCPillAccent.PRIMARY -> if (boldMode) {
            KKCPillStyle(
                container = container,
                containerBorder = containerBorder,
                filledContainer = false,
                fill = boldGradientBrush(palette),
                fillColor = palette.primary,
                border = palette.primary.copy(alpha = 0.6f),
                selectedText = boldChipTextColor(palette),
                unselectedText = onSurface
            )
        } else {
            KKCPillStyle(
                container = container,
                containerBorder = containerBorder,
                filledContainer = false,
                fill = SolidColor(palette.primary.copy(alpha = 0.18f)),
                fillColor = palette.primary.copy(alpha = 0.18f),
                border = palette.primary.copy(alpha = 0.5f),
                selectedText = onSurface,
                unselectedText = onSurface
            )
        }

        KKCPillAccent.ATTENTION -> if (boldMode) {
            KKCPillStyle(
                container = container,
                containerBorder = containerBorder,
                filledContainer = false,
                fill = SolidColor(status.skip),
                fillColor = status.skip,
                border = status.skip.copy(alpha = 0.6f),
                selectedText = contrastOn(status.skip),
                unselectedText = status.skip
            )
        } else {
            KKCPillStyle(
                container = container,
                containerBorder = containerBorder,
                filledContainer = false,
                fill = SolidColor(status.skip.copy(alpha = 0.2f)),
                fillColor = status.skip.copy(alpha = 0.2f),
                border = status.skip.copy(alpha = 0.55f),
                selectedText = onSurface,
                unselectedText = status.skip
            )
        }
    }
}

@Composable
fun rememberKKCPillStyle(accent: KKCPillAccent = KKCPillAccent.PRIMARY): KKCPillStyle {
    val tokens = LocalKKCThemeTokens.current
    val dark = LocalKKCIsDarkTheme.current
    val status = KKCThemeColors.statusColors
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    return remember(tokens, dark, status, onSurface, surface, outlineVariant, accent) {
        kkcPillStyle(tokens.palette(dark), tokens.boldMode, status, onSurface, surface, outlineVariant, accent)
    }
}

/** The track a sliding pill rides on. */
@Composable
fun KKCPillContainer(
    style: KKCPillStyle,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = style.container,
        border = BorderStroke(1.dp, style.containerBorder),
        shadowElevation = 3.5.dp,
        tonalElevation = 2.dp,
        modifier = modifier
    ) {
        content()
    }
}

/**
 * Whole control bar hosting sliding tabs plus other controls (page count, fullscreen, ...). With a
 * two-color theme the entire bar becomes the primary-color track and its icons/text switch to a
 * contrasting color; otherwise it is the usual neutral surface. Tabs inside should be bare
 * ([KKCSlidingTabRow] without a track) so the bar itself is the track.
 */
@Composable
fun KKCPillBarSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    shadowElevation: Dp = 2.dp,
    neutralBorder: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable () -> Unit
) {
    val style = rememberKKCPillStyle()
    Surface(
        modifier = modifier,
        shape = shape,
        color = if (style.filledContainer) style.container else MaterialTheme.colorScheme.surface,
        shadowElevation = shadowElevation,
        border = BorderStroke(1.dp, if (style.filledContainer) style.containerBorder else neutralBorder)
    ) {
        if (style.filledContainer) {
            CompositionLocalProvider(LocalContentColor provides style.unselectedText, content = content)
        } else {
            content()
        }
    }
}

/**
 * Track for a strip that already sits inside another container: themes with a filled (primary)
 * track get one, single-color themes render the strip bare on the host's own surface.
 */
@Composable
fun KKCPillTrack(
    style: KKCPillStyle,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (style.filledContainer) {
        KKCPillContainer(style = style, modifier = modifier, content = content)
    } else {
        content()
    }
}

/** Single toggle styled like the sliding control: track plus a pill that is filled while [selected]. */
@Composable
fun KKCPillToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable (Color) -> Unit)? = null
) {
    val style = rememberKKCPillStyle()
    val textColor = if (selected) style.selectedText else style.unselectedText
    KKCPillContainer(style = style, modifier = modifier.height(36.dp)) {
        Box(modifier = Modifier.padding(2.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(32.dp)
                    .then(if (selected) Modifier.kkcPillIndicator(style) else Modifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                    .padding(horizontal = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor,
                        maxLines = 1
                    )
                    trailingIcon?.invoke(textColor)
                }
            }
        }
    }
}

/** The sliding pill itself — fill + outline in the resolved [style]. */
fun Modifier.kkcPillIndicator(style: KKCPillStyle): Modifier {
    val shape = RoundedCornerShape(6.dp)
    return this
        .background(brush = style.fill, shape = shape)
        .border(1.dp, style.border, shape)
}

/**
 * Last indicator position per [KKCSlidingPillRow] `persistKey`. Lets a pill that is disposed and
 * recreated on a selection change (a screen swapped by a `when`, like the flexible-mode Dashboard)
 * still slide from where it was instead of appearing at the new option.
 */
private val lastPillBounds = mutableMapOf<String, Pair<Dp, Dp>>()

data class KKCPillOption(
    val label: String,
    val isSelected: Boolean,
    val onClick: () -> Unit,
    val enabled: Boolean = true
)

/**
 * Segmented control whose selection pill slides between options. This is the hardwoods doc-controls
 * (Assembly / Plans & Elevs. / View 3D) look, shared so header buttons match it and both follow the
 * active theme.
 */
@Composable
fun KKCSlidingPillRow(
    options: List<KKCPillOption>,
    modifier: Modifier = Modifier,
    accent: KKCPillAccent = KKCPillAccent.PRIMARY,
    persistKey: String? = null
) {
    if (options.isEmpty()) return
    val style = rememberKKCPillStyle(accent)
    val lowEnd = LocalLowEndMode.current
    val density = LocalDensity.current
    val selectedIndex = remember(options) { options.indexOfFirst { it.isSelected }.coerceAtLeast(0) }
    var itemBounds by remember { mutableStateOf(mapOf<Int, Pair<Dp, Dp>>()) }

    KKCPillContainer(
        style = style,
        modifier = modifier.height(36.dp).wrapContentWidth()
    ) {
        Box(
            modifier = Modifier
                .height(36.dp)
                .wrapContentWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp)
        ) {
            val currentBounds = itemBounds[selectedIndex]
            val animLeft = remember { Animatable(0.dp, Dp.VectorConverter) }
            val animWidth = remember { Animatable(0.dp, Dp.VectorConverter) }
            var seeded by remember { mutableStateOf(false) }
            LaunchedEffect(currentBounds) {
                if (currentBounds == null) return@LaunchedEffect
                if (!seeded) {
                    val start = persistKey?.let { lastPillBounds[it] } ?: currentBounds
                    animLeft.snapTo(start.first)
                    animWidth.snapTo(start.second)
                    seeded = true
                }
                persistKey?.let { lastPillBounds[it] = currentBounds }
                val spec = if (lowEnd.animationsDisabled) {
                    snap<Dp>()
                } else {
                    tween<Dp>(durationMillis = 420, easing = FastOutSlowInEasing)
                }
                coroutineScope {
                    launch { animLeft.animateTo(currentBounds.first, spec) }
                    launch { animWidth.animateTo(currentBounds.second, spec) }
                }
            }
            if (seeded) {
                Box(
                    Modifier
                        .offset(x = animLeft.value)
                        .width(animWidth.value)
                        .height(32.dp)
                        .kkcPillIndicator(style)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(32.dp)
                    .wrapContentWidth()
            ) {
                options.forEachIndexed { idx, opt ->
                    val isSelected = selectedIndex == idx
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(32.dp)
                            .zIndex(1f)
                            .onGloballyPositioned { coordinates ->
                                val leftDp = with(density) { coordinates.positionInParent().x.toDp() }
                                val widthDp = with(density) { coordinates.size.width.toDp() }
                                itemBounds = itemBounds + (idx to (leftDp to widthDp))
                            }
                            .clickable(
                                enabled = opt.enabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { opt.onClick() }
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = opt.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                isSelected -> style.selectedText
                                opt.enabled -> style.unselectedText
                                else -> style.unselectedText.copy(alpha = 0.38f)
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

data class KKCTabItem(
    val label: String,
    val isSelected: Boolean,
    val onClick: () -> Unit,
    val accent: KKCPillAccent = KKCPillAccent.PRIMARY,
    val alwaysBold: Boolean = false
)

/**
 * Horizontally scrollable tab strip with the same sliding pill as [KKCSlidingPillRow], for the
 * hardwoods cutlist tabs (no container of its own — the caller supplies it). Pill and labels share
 * one 40dp centerline, so the pill stays centered on its label at any position. The pill takes the
 * selected item's [KKCTabItem.accent].
 */
@Composable
fun KKCSlidingTabRow(
    items: List<KKCTabItem>,
    modifier: Modifier = Modifier,
    /** False when the host already scrolls horizontally (nesting two scrollers crashes). */
    scrollable: Boolean = true
) {
    if (items.isEmpty()) return
    val primaryStyle = rememberKKCPillStyle(KKCPillAccent.PRIMARY)
    val attentionStyle = rememberKKCPillStyle(KKCPillAccent.ATTENTION)
    fun styleFor(item: KKCTabItem) = if (item.accent == KKCPillAccent.ATTENTION) attentionStyle else primaryStyle
    val lowEnd = LocalLowEndMode.current
    val density = LocalDensity.current
    val selectedIndex = remember(items) { items.indexOfFirst { it.isSelected } }
    var itemBounds by remember { mutableStateOf(mapOf<Int, Pair<Dp, Dp>>()) }
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .height(40.dp)
            .then(if (scrollable) Modifier.horizontalScroll(scrollState) else Modifier)
            .padding(horizontal = 4.dp)
    ) {
        val currentBounds = if (selectedIndex >= 0) itemBounds[selectedIndex] else null
        val animLeft = remember { Animatable(0.dp, Dp.VectorConverter) }
        val animWidth = remember { Animatable(0.dp, Dp.VectorConverter) }
        var seeded by remember { mutableStateOf(false) }
        LaunchedEffect(currentBounds) {
            if (currentBounds == null) return@LaunchedEffect
            if (!seeded) {
                animLeft.snapTo(currentBounds.first)
                animWidth.snapTo(currentBounds.second)
                seeded = true
            }
            val spec = if (lowEnd.animationsDisabled) {
                snap<Dp>()
            } else {
                tween<Dp>(durationMillis = 420, easing = FastOutSlowInEasing)
            }
            coroutineScope {
                launch { animLeft.animateTo(currentBounds.first, spec) }
                launch { animWidth.animateTo(currentBounds.second, spec) }
            }
        }
        if (seeded && selectedIndex >= 0) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = animLeft.value)
                    .width(animWidth.value)
                    .height(32.dp)
                    .kkcPillIndicator(styleFor(items[selectedIndex]))
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(40.dp)
        ) {
            items.forEachIndexed { idx, item ->
                val style = styleFor(item)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(32.dp)
                        .zIndex(1f)
                        .onGloballyPositioned { coordinates ->
                            val leftDp = with(density) { coordinates.positionInParent().x.toDp() }
                            val widthDp = with(density) { coordinates.size.width.toDp() }
                            itemBounds = itemBounds + (idx to (leftDp to widthDp))
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { item.onClick() }
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = when {
                            item.alwaysBold -> FontWeight.Bold
                            item.isSelected -> FontWeight.SemiBold
                            else -> FontWeight.Normal
                        },
                        color = if (item.isSelected) style.selectedText else style.unselectedText,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

data class KKCPillAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null
)

/**
 * A row of action buttons (open a PDF, print, ...) styled like the sliding control but with every
 * button showing the pill — nothing slides because none of them is "selected". Same theme rules as
 * the sliders: primary-colored track and secondary-colored pills on two-color themes.
 */
@Composable
fun KKCPillActionRow(
    actions: List<KKCPillAction>,
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return
    val style = rememberKKCPillStyle()
    // Outer padding equals the gap between buttons so the track border reads as an even frame.
    val gap = 4.dp
    KKCPillContainer(style = style, modifier = modifier.height(32.dp + gap * 2)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(gap)
        ) {
            actions.forEach { action ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(32.dp)
                        .kkcPillIndicator(style)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = action.onClick)
                        .padding(horizontal = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (action.icon != null) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = style.selectedText,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = style.selectedText,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Vertical variant of the sliding selector: options stacked top to bottom (text left-aligned), one
 * pill sliding up and down behind them. Rows have a fixed height, so the pill position is pure
 * arithmetic (index * pitch) and animates with Compose -- used natively over the 3D web view,
 * where animating a DOM pill inside the WebView made the whole overlay flicker.
 */
@Composable
fun KKCVerticalSlidingPillColumn(
    options: List<KKCPillOption>,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    val style = rememberKKCPillStyle()
    val lowEnd = LocalLowEndMode.current
    val selectedIndex = remember(options) { options.indexOfFirst { it.isSelected } }
    val rowHeight = 30.dp
    val rowGap = 4.dp
    val pillTop by animateDpAsState(
        targetValue = (rowHeight + rowGap) * selectedIndex.coerceAtLeast(0),
        animationSpec = if (lowEnd.animationsDisabled) snap() else tween(380, easing = FastOutSlowInEasing),
        label = "verticalPillTop"
    )
    KKCPillContainer(style = style, modifier = modifier) {
        Box(modifier = Modifier.width(IntrinsicSize.Max).padding(4.dp)) {
            if (selectedIndex >= 0) {
                Box(
                    Modifier
                        .offset(y = pillTop)
                        .fillMaxWidth()
                        .height(rowHeight)
                        .kkcPillIndicator(style)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
                options.forEach { opt ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .clickable(
                                enabled = opt.enabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = opt.onClick
                            )
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = opt.label,
                            style = MaterialTheme.typography.labelMedium,
                            // Constant weight: a bolder active label would widen the widest row and
                            // make the whole column (and pill) jump on every switch.
                            fontWeight = FontWeight.Bold,
                            color = if (opt.isSelected) style.selectedText else style.unselectedText,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
