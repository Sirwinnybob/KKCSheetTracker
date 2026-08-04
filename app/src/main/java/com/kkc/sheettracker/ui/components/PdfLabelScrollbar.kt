package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.viewer.NavigatorRowModel

internal fun segmentIndexForOffsetFraction(segmentCount: Int, fraction: Float): Int {
    if (segmentCount <= 0) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    return (clamped * segmentCount).toInt().coerceIn(0, segmentCount - 1)
}

@Composable
internal fun PdfLabelScrollbar(
    modifier: Modifier = Modifier,
    rows: List<NavigatorRowModel>,
    currentPage: Int,
    onPageSelected: (Int) -> Unit
) {
    if (rows.isEmpty()) return
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableStateOf(0f) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(44.dp)
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .pointerInput(rows.size) {
                detectTapGestures { offset ->
                    if (trackHeightPx <= 0f) return@detectTapGestures
                    val fraction = (offset.y / trackHeightPx).coerceIn(0f, 1f)
                    val index = segmentIndexForOffsetFraction(rows.size, fraction)
                    onPageSelected(rows[index].page)
                }
            }
            .pointerInput(rows.size) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        if (trackHeightPx > 0f) dragFraction = (offset.y / trackHeightPx).coerceIn(0f, 1f)
                    },
                    onVerticalDrag = { change, _ ->
                        if (trackHeightPx <= 0f) return@detectVerticalDragGestures
                        val fraction = (change.position.y / trackHeightPx).coerceIn(0f, 1f)
                        dragFraction = fraction
                        val index = segmentIndexForOffsetFraction(rows.size, fraction)
                        onPageSelected(rows[index].page)
                    },
                    onDragEnd = { dragFraction = null },
                    onDragCancel = { dragFraction = null }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(4.dp)
                .align(Alignment.CenterEnd)
                .background(MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(2.dp))
        )

        val activeFraction = dragFraction
        if (activeFraction != null) {
            val index = segmentIndexForOffsetFraction(rows.size, activeFraction)
            val label = rows[index].primaryLabel
            // Position the callout vertically centered on the finger: convert the finger's
            // pixel offset along the track (activeFraction * trackHeightPx) to dp via the
            // current density, then shift up by half the callout's approximate height (~32dp)
            // so the callout is centered on the touch point rather than starting there.
            val fingerOffsetDp = with(density) { (activeFraction * trackHeightPx).toDp() }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-32).dp, y = fingerOffsetDp - 16.dp)
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
