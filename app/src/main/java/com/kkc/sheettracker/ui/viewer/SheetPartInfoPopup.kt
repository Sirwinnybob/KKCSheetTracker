package com.kkc.sheettracker.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.Part
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import com.kkc.sheettracker.ui.theme.hueOf
import com.kkc.sheettracker.ui.theme.kkcFrostedBaseColor
import com.kkc.sheettracker.ui.theme.kkcFrostedContentColor
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt

internal fun sheetPartInfoText(part: Part): String = buildString {
    fun dimension(value: Double) = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    append("Cab# ${part.cabNumber} - ${part.name} - W:${dimension(part.width)} L:${dimension(part.length)}")
    if (part.room.isNotBlank()) append(" - ${part.room.trim()}")
}

/** Keep the red rotation cue except where the glass itself is red. */
internal fun sheetPartRotationColor(background: Color, contentColor: Color): Color {
    val chroma = maxOf(background.red, background.green, background.blue) -
        minOf(background.red, background.green, background.blue)
    val hue = hueOf(background)
    val redGlass = chroma >= 0.15f && (hue <= 20f || hue >= 340f)
    return if (redGlass) contentColor else Color.Red
}

/** Stay beside the selected part; prefer free space among equally close positions. */
internal fun sheetPartPopupPosition(
    viewport: IntSize,
    popup: IntSize,
    anchor: Rect?,
    occupied: List<Rect>,
    gap: Float
): IntOffset {
    val marginX = gap.coerceAtMost(((viewport.width - popup.width) / 2f).coerceAtLeast(0f))
    val marginY = gap.coerceAtMost(((viewport.height - popup.height) / 2f).coerceAtLeast(0f))
    val maxX = (viewport.width - popup.width - marginX).coerceAtLeast(marginX)
    val maxY = (viewport.height - popup.height - marginY).coerceAtLeast(marginY)
    if (anchor == null) return IntOffset(marginX.roundToInt(), marginY.roundToInt())
    val width = popup.width.toFloat()
    val height = popup.height.toFloat()
    val centerX = anchor.center.x - width / 2f
    val centerY = anchor.center.y - height / 2f
    val candidates = buildList {
        for (x in listOf(centerX, anchor.left, anchor.right - width)) {
            add(x to anchor.top - height - gap)
            add(x to anchor.bottom + gap)
        }
        for (y in listOf(centerY, anchor.top, anchor.bottom - height)) {
            add(anchor.right + gap to y)
            add(anchor.left - width - gap to y)
        }
        for (box in occupied) {
            add(centerX to box.top - height - gap)
            add(centerX to box.bottom + gap)
            add(box.left - width - gap to centerY)
            add(box.right + gap to centerY)
        }
        add(marginX to marginY)
        add(maxX to marginY)
        add(marginX to maxY)
        add(maxX to maxY)
    }.map { (x, y) -> Rect(x.coerceIn(marginX, maxX), y.coerceIn(marginY, maxY),
        x.coerceIn(marginX, maxX) + width, y.coerceIn(marginY, maxY) + height) }
    fun overlap(a: Rect, b: Rect): Float =
        (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
    val best = candidates.minWith(compareBy<Rect>(
        { overlap(it, anchor) },
        {
            val dx = maxOf(anchor.left - it.right, it.left - anchor.right, 0f)
            val dy = maxOf(anchor.top - it.bottom, it.top - anchor.bottom, 0f)
            (dx * dx + dy * dy - gap * gap).coerceAtLeast(0f)
        },
        { candidate -> occupied.sumOf { overlap(candidate, it).toDouble() } },
        { (it.center - anchor.center).getDistanceSquared() }
    ))
    return IntOffset(best.left.roundToInt(), best.top.roundToInt())
}

@Composable
internal fun SheetPartInfoPopup(
    part: Part,
    anchor: Rect?,
    occupied: List<Rect>,
    viewport: IntSize,
    hazeState: HazeState
) {
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.toPx() }
    var popupSize by remember { mutableStateOf(IntSize.Zero) }
    val position = sheetPartPopupPosition(viewport, popupSize, anchor, occupied, gap)
    val lowEnd = LocalLowEndMode.current
    val theme = LocalKKCThemeTokens.current
    val frosted = theme.frosted
    val baseColor = kkcFrostedBaseColor()
    val contentColor = kkcFrostedContentColor()
    val shape = MaterialTheme.shapes.medium
    val scrollState = key(part.number) { rememberScrollState() }
    val glass = if (!lowEnd.blurDisabled) {
        Modifier.hazeEffect(hazeState, style = HazeDefaults.style(
            backgroundColor = baseColor.copy(alpha = frosted.backgroundAlpha.coerceIn(0.5f, 0.95f)),
            blurRadius = frosted.blurDp.coerceAtLeast(1f).dp
        ))
    } else Modifier.background(baseColor)
    Row(
        modifier = Modifier
            .offset { position }
            .widthIn(max = with(density) { (viewport.width - gap * 2).coerceAtLeast(0f).toDp() }.coerceAtMost(360.dp))
            .heightIn(max = with(density) { (viewport.height - gap * 2).coerceAtLeast(0f).toDp() })
            .onSizeChanged { popupSize = it }
            .shadow(if (lowEnd.shadowsDisabled || lowEnd.webViewBlurSuppressed) 0.dp else 3.dp, shape, clip = false)
            .clip(shape)
            .then(glass)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(sheetPartInfoText(part), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, color = contentColor)
        if (partMarkers(part.rotated, part.banding).isNotEmpty()) {
            PartMarkers(part.rotated, part.banding, Modifier.width(28.dp),
                rotationColor = sheetPartRotationColor(baseColor, contentColor))
        }
    }
}
