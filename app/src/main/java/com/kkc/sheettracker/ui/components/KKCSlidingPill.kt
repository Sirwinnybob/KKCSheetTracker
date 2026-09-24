package com.kkc.sheettracker.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import kotlin.math.roundToInt
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.IntOffset
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
internal fun contrastOn(color: Color): Color = if (color.luminance() > 0.179f) Color.Black else Color.White

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
 * Last pill position (fractional item index) per sliding-row `persistKey`. Lets a pill that is
 * disposed and recreated on a selection change (a screen swapped by a `when`, like the
 * flexible-mode Dashboard) still slide from where it was instead of appearing at the new option.
 */
private val lastPillIndex = mutableMapOf<String, Float>()

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
 *
 * Just the track around [KKCSlidingTabRow]: all sliding behavior (pill motion, per-letter label
 * color, stable label widths) lives there, so every slider in the app improves together.
 */
@Composable
fun KKCSlidingPillRow(
    options: List<KKCPillOption>,
    modifier: Modifier = Modifier,
    accent: KKCPillAccent = KKCPillAccent.PRIMARY,
    persistKey: String? = null,
    /** Stretch to the parent's width with equally wide segments (instead of hugging the labels). */
    fillWidth: Boolean = false
) {
    if (options.isEmpty()) return
    val style = rememberKKCPillStyle(accent)
    val widthMod = if (fillWidth) Modifier.fillMaxWidth() else Modifier.wrapContentWidth()
    KKCPillContainer(
        style = style,
        modifier = modifier.height(36.dp).then(widthMod)
    ) {
        KKCSlidingTabRow(
            items = options.map { opt ->
                KKCTabItem(
                    label = opt.label,
                    isSelected = opt.isSelected,
                    onClick = opt.onClick,
                    accent = accent,
                    enabled = opt.enabled
                )
            },
            modifier = widthMod,
            scrollable = false,
            height = 36.dp,
            edgePadding = 2.dp,
            itemPadding = 12.dp,
            fillWidth = fillWidth,
            persistKey = persistKey
        )
    }
}

data class KKCTabItem(
    val label: String,
    val isSelected: Boolean,
    val onClick: () -> Unit,
    val accent: KKCPillAccent = KKCPillAccent.PRIMARY,
    val alwaysBold: Boolean = false,
    /** Red count badge after the label (0 = none). */
    val badgeCount: Int = 0,
    /**
     * Always reserve room for a two-digit badge, so the tab (and every tab after it) doesn't
     * shift when a count appears or changes.
     */
    val reserveBadge: Boolean = false,
    /** Small bell after the label (e.g. a subscribed supply category). */
    val showBell: Boolean = false,
    val enabled: Boolean = true
)

/**
 * The app's one horizontal sliding-pill engine: tab strips (supply, hardwoods cutlist, ...) use it
 * directly with their own container, and [KKCSlidingPillRow] wraps it in a track. Improve sliding
 * behavior here and every slider gets it.
 *
 * - The pill glides (spring) between items, moved in layout/draw only -- no per-frame recomposition.
 * - Labels change color letter by letter as the pill's edge crosses them.
 * - Labels never shift: selection doesn't change a label's width, and a scrollable strip scrolls
 *   in proportion to the pill instead of re-centering on each selection.
 *
 * Pill and labels share one centerline, so the pill stays centered on its label at any position.
 * The pill takes the selected item's [KKCTabItem.accent].
 */
@Composable
fun KKCSlidingTabRow(
    items: List<KKCTabItem>,
    modifier: Modifier = Modifier,
    /** False when the host already scrolls horizontally (nesting two scrollers crashes). */
    scrollable: Boolean = true,
    /**
     * Live fractional tab index while the host content is being scrolled (e.g. a swiped board),
     * or null when it is at rest. While non-null the pill tracks it exactly; once it returns to
     * null the pill springs onto the selected tab.
     */
    trackingPosition: (() -> Float?)? = null,
    /** Strip height; the pill is 32dp tall, centered in it. */
    height: Dp = 40.dp,
    /** Space before the first and after the last item. */
    edgePadding: Dp = 4.dp,
    /** Horizontal padding inside each item, around its label. */
    itemPadding: Dp = 8.dp,
    /** Equal-width items filling the strip's width (not scrollable). */
    fillWidth: Boolean = false,
    /** Remember the pill position under this key so a recreated strip slides from where it was. */
    persistKey: String? = null
) {
    if (items.isEmpty()) return
    val primaryStyle = rememberKKCPillStyle(KKCPillAccent.PRIMARY)
    val attentionStyle = rememberKKCPillStyle(KKCPillAccent.ATTENTION)
    fun styleFor(item: KKCTabItem) = if (item.accent == KKCPillAccent.ATTENTION) attentionStyle else primaryStyle
    val lowEnd = LocalLowEndMode.current
    val density = LocalDensity.current
    val selectedIndex = remember(items) { items.indexOfFirst { it.isSelected } }
    // Each tab's left edge and width in px, relative to the label row.
    var itemBounds by remember { mutableStateOf(mapOf<Int, Pair<Float, Float>>()) }
    val scrollState = rememberScrollState()
    var viewportPx by remember { mutableIntStateOf(0) }
    val canScroll = scrollable && !fillWidth

    // The pill's position is one animated fractional tab index; its CENTER follows the tab
    // centers interpolated between the neighbouring tabs, and a spring keeps its velocity when the
    // target moves again mid-flight -- e.g. while the selection follows a scrolling board -- so it
    // stays smooth instead of restarting a fixed-length tween.
    //
    // The pill keeps its size while it travels and only grows/shrinks to the new tab's width once
    // it has settled -- i.e. once the user stops scrolling the board or tapping around.
    val position = remember { Animatable(0f) }
    val pillWidth = remember { Animatable(0f) } // px
    var seeded by remember { mutableStateOf(false) }
    // True while the host drives the pill through [trackingPosition].
    var tracking by remember { mutableStateOf(false) }
    val currentTrackingPosition by rememberUpdatedState(trackingPosition)
    val currentItemCount by rememberUpdatedState(items.size)
    if (trackingPosition != null && !lowEnd.animationsDisabled) {
        LaunchedEffect(Unit) {
            snapshotFlow { currentTrackingPosition?.invoke() }.collect { raw ->
                if (raw == null || !seeded) {
                    tracking = false
                    return@collect
                }
                val lastIndex = (currentItemCount - 1).coerceAtLeast(0)
                val p = raw.coerceIn(0f, lastIndex.toFloat())
                val lower = p.toInt().coerceIn(0, lastIndex)
                val upper = (lower + 1).coerceAtMost(lastIndex)
                val a = itemBounds[lower]?.second
                val b = itemBounds[upper]?.second
                tracking = true
                position.snapTo(p)
                // Width morphs between neighbouring tabs while following a drag.
                if (a != null && b != null) pillWidth.snapTo(a + (b - a) * (p - lower))
            }
        }
    }
    val targetWidth = if (selectedIndex >= 0) itemBounds[selectedIndex]?.second else null
    LaunchedEffect(selectedIndex, targetWidth, tracking) {
        val target = targetWidth ?: return@LaunchedEffect
        if (tracking && seeded) return@LaunchedEffect
        if (!seeded) {
            // A recreated strip starts where its predecessor's pill was and slides from there.
            val start = persistKey?.let { lastPillIndex[it] }
                ?.coerceIn(0f, items.lastIndex.toFloat())
                ?: selectedIndex.toFloat()
            position.snapTo(start)
            pillWidth.snapTo(target)
            seeded = true
        }
        persistKey?.let { lastPillIndex[it] = selectedIndex.toFloat() }
        if (lowEnd.animationsDisabled) {
            position.snapTo(selectedIndex.toFloat())
            pillWidth.snapTo(target)
        } else {
            position.animateTo(
                selectedIndex.toFloat(),
                spring(dampingRatio = 1f, stiffness = 260f)
            )
            // Only reached when this animation was not superseded by a newer selection.
            pillWidth.animateTo(target, tween(260, easing = FastOutSlowInEasing))
        }
    }

    // Pill left edge and width (px, label-row coordinates) right now, or null while hidden. Only
    // read from layout/draw/snapshotFlow lambdas: the animation then re-lays-out and redraws the
    // strip each frame without recomposing every tab, which is what made the slide stutter.
    fun pillSpan(): Pair<Float, Float>? {
        if (!seeded || selectedIndex < 0) return null
        val lastIndex = items.lastIndex
        val p = position.value.coerceIn(0f, lastIndex.toFloat())
        val lower = p.toInt().coerceIn(0, lastIndex)
        val upper = (lower + 1).coerceAtMost(lastIndex)
        val a = itemBounds[lower] ?: return null
        val b = itemBounds[upper] ?: return null
        val fraction = p - lower
        val aCenter = a.first + a.second / 2
        val center = aCenter + ((b.first + b.second / 2) - aCenter) * fraction
        val width = pillWidth.value
        return (center - width / 2) to width
    }

    // The strip scrolls in proportion to the pill's position along it: pill at the start -> strip
    // at the start, pill at the end -> strip at the end, gliding continuously in between as the pill
    // moves (so it moves with a panned board rather than only when the pill nears an edge). The
    // pill always stays in view, and a pill that barely moves only nudges the labels.
    if (canScroll) {
        LaunchedEffect(selectedIndex, items.size) {
            snapshotFlow {
                val span = pillSpan()
                val max = scrollState.maxValue
                val first = itemBounds[0]
                val last = itemBounds[items.lastIndex]
                if (span == null || viewportPx <= 0 || max <= 0 || first == null || last == null) {
                    null
                } else {
                    // Measured from the first tab's center to the last tab's, so the end tabs
                    // are fully in view (not clipped) when the pill sits on them.
                    val firstCenter = first.first + first.second / 2
                    val lastCenter = last.first + last.second / 2
                    val center = span.first + span.second / 2
                    val t = if (lastCenter > firstCenter) (center - firstCenter) / (lastCenter - firstCenter) else 0f
                    (t.coerceIn(0f, 1f) * max).roundToInt()
                }
            }.collect { target -> if (target != null && target != scrollState.value) scrollState.scrollTo(target) }
        }
    }

    Box(
        modifier = modifier
            .height(height)
            .onSizeChanged { viewportPx = it.width }
            .then(if (canScroll) Modifier.horizontalScroll(scrollState) else Modifier)
            .padding(horizontal = edgePadding)
    ) {
        if (selectedIndex >= 0) {
            val pillHeight = 32.dp
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .layout { measurable, _ ->
                        val span = pillSpan()
                        val h = pillHeight.roundToPx()
                        val w = span?.second?.roundToInt()?.coerceAtLeast(0) ?: 0
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        // Zero width so the pill never affects the strip's own size.
                        layout(0, h) {
                            if (span != null) placeable.place(span.first.roundToInt(), 0)
                        }
                    }
                    .kkcPillIndicator(styleFor(items[selectedIndex]))
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(height)
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
        ) {
            items.forEachIndexed { idx, item ->
                val style = styleFor(item)
                val onPill = if (selectedIndex >= 0) styleFor(items[selectedIndex]).selectedText else style.selectedText
                val baseColor = if (item.enabled) style.unselectedText else style.unselectedText.copy(alpha = 0.38f)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .then(if (fillWidth) Modifier.weight(1f) else Modifier)
                        .height(32.dp)
                        .zIndex(1f)
                        .onGloballyPositioned { coordinates ->
                            val left = coordinates.positionInParent().x
                            val width = coordinates.size.width.toFloat()
                            itemBounds = itemBounds + (idx to (left to width))
                        }
                        .clickable(
                            enabled = item.enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { item.onClick() }
                ) {
                    KKCTabLabel(item, baseColor, Modifier.padding(horizontal = itemPadding))
                    // The same label in the on-pill color, clipped to wherever the pill is right
                    // now: each letter flips color the moment the pill's edge crosses it.
                    KKCTabLabel(
                        item,
                        onPill,
                        Modifier
                            .clearAndSetSemantics { }
                            .drawWithContent {
                                val span = pillSpan() ?: return@drawWithContent
                                val bounds = itemBounds[idx] ?: return@drawWithContent
                                val left = span.first - bounds.first
                                clipRect(left = left, right = left + span.second) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                            .padding(horizontal = itemPadding)
                    )
                }
            }
        }
    }
}

@Composable
private fun KKCTabLabel(item: KKCTabItem, color: Color, modifier: Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        if (item.alwaysBold) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1
            )
        } else {
            // The selected label is semibold; an invisible semibold copy holds that width for
            // every state, so selecting a tab never widens it and shifts the labels after it.
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.alpha(0f)
                )
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (item.isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = color,
                    maxLines = 1
                )
            }
        }
        if (item.reserveBadge) {
            Box(contentAlignment = Alignment.Center) {
                // Invisible two-digit badge sets the reserved width; the real one centers in it.
                androidx.compose.material3.Badge(modifier = Modifier.alpha(0f)) { Text("88") }
                if (item.badgeCount > 0) {
                    androidx.compose.material3.Badge { Text(item.badgeCount.toString()) }
                }
            }
        } else if (item.badgeCount > 0) {
            androidx.compose.material3.Badge { Text(item.badgeCount.toString()) }
        }
        if (item.showBell) {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = "Subscribed",
                tint = color,
                modifier = Modifier.size(12.dp)
            )
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
    // Read only inside layout/draw lambdas, so the slide doesn't recompose the column each frame
    // (same approach as [KKCSlidingTabRow]).
    val pillTop = animateDpAsState(
        targetValue = (rowHeight + rowGap) * selectedIndex.coerceAtLeast(0),
        animationSpec = if (lowEnd.animationsDisabled) snap() else spring(dampingRatio = 1f, stiffness = 260f),
        label = "verticalPillTop"
    )
    KKCPillContainer(style = style, modifier = modifier) {
        Box(modifier = Modifier.width(IntrinsicSize.Max).padding(4.dp)) {
            if (selectedIndex >= 0) {
                Box(
                    Modifier
                        .offset { IntOffset(0, pillTop.value.roundToPx()) }
                        .fillMaxWidth()
                        .height(rowHeight)
                        .kkcPillIndicator(style)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
                options.forEachIndexed { idx, opt ->
                    val baseColor = if (opt.enabled) style.unselectedText else style.unselectedText.copy(alpha = 0.38f)
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
                    ) {
                        // Constant weight: a bolder active label would widen the widest row and
                        // make the whole column (and pill) jump on every switch.
                        Text(
                            text = opt.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = baseColor,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        // On-pill copy clipped to the pill: the label changes color line by line
                        // as the pill slides over it.
                        Text(
                            text = opt.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = style.selectedText,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clearAndSetSemantics { }
                                .drawWithContent {
                                    if (selectedIndex < 0) return@drawWithContent
                                    val rowTop = ((rowHeight + rowGap) * idx).toPx()
                                    // This text is vertically centered in its row.
                                    val textTop = rowTop + (rowHeight.toPx() - size.height) / 2
                                    val top = pillTop.value.toPx() - textTop
                                    clipRect(top = top, bottom = top + rowHeight.toPx()) {
                                        this@drawWithContent.drawContent()
                                    }
                                }
                                .padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
