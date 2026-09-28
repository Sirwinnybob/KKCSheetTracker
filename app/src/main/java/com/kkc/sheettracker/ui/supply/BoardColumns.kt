package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.FlowColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.components.kkcCardDepth
import kotlin.math.roundToInt

/** Card width inside a board column (the Supply board and the specialty kanban board). */
internal val BOARD_CARD_WIDTH = 300.dp

private val BoardColumnShape = RoundedCornerShape(8.dp)

/**
 * Board columns side by side in a plain (non-lazy) horizontally scrolling row, driven by [board].
 * Every column stays composed, so panning never composes one mid-swipe: the first
 * [initialBuiltColumns] are built right away and the rest one per frame. Each column reports its
 * position to [board] through the `placed` modifier [column] must apply to its root.
 *
 * [modifier] sizes and pads the row (the part [board] measures as its viewport);
 * [scrollModifier] goes between that and the horizontal scroll (e.g. a nested-scroll connection).
 */
@Composable
internal fun BoardColumnsRow(
    board: SupplyBoardState,
    keys: List<String>,
    initialBuiltColumns: Int,
    modifier: Modifier = Modifier,
    scrollModifier: Modifier = Modifier,
    column: @Composable (key: String, placed: Modifier) -> Unit
) {
    LaunchedEffect(keys) { board.updateKeys(keys) }
    // Each column reads the built count through its own derived state, so building one more
    // column recomposes only that column's slot, not the whole row.
    val builtColumns = remember { mutableIntStateOf(initialBuiltColumns) }
    LaunchedEffect(keys.size) {
        while (builtColumns.intValue < keys.size) {
            withFrameNanos { }
            builtColumns.intValue++
        }
    }
    Row(
        modifier = modifier
            .onSizeChanged { board.viewportPx = it.width }
            .then(scrollModifier)
            .horizontalScroll(board.scroll),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 4dp + 12dp spacing = 16dp edge inset, matching SupplyBoardState's edge; kept as a
        // spacer so column positions are plain row coordinates.
        Spacer(Modifier.width(4.dp))
        keys.forEachIndexed { index, columnKey ->
            key(columnKey) {
                StaggeredColumnSlot(index, builtColumns) {
                    column(
                        columnKey,
                        Modifier.onPlaced { coords ->
                            board.columns[columnKey] = coords.positionInParent().x.roundToInt() to coords.size.width
                        }
                    )
                }
            }
        }
        // Room to scroll past the last column (60% of the viewport) so the last columns can reach
        // the left edge and become active. Read during layout, so a resize doesn't recompose.
        Spacer(
            Modifier.layout { _, _ ->
                layout((board.viewportPx * 0.6f).roundToInt(), 0) {}
            }
        )
    }
}

/** Composes [content] once the staggered build has reached column [index]. */
@Composable
private fun StaggeredColumnSlot(index: Int, builtColumns: IntState, content: @Composable () -> Unit) {
    val built by remember(index, builtColumns) { derivedStateOf { builtColumns.intValue > index } }
    if (built) content()
}

/**
 * One board column: [header] pinned to the top over [body], which fills the board's height. The
 * column grows wider (not taller) as it fills; see [BoardCardFlow] and [CategoryColumnLayout].
 */
@Composable
internal fun BoardColumnCard(
    containerColor: Color,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxHeight()
            .wrapContentWidth()
            .kkcCardDepth(BoardColumnShape, elevation = 3.dp),
        shape = BoardColumnShape,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        CategoryColumnLayout(
            modifier = Modifier.fillMaxHeight(),
            header = header,
            content = body
        )
    }
}

/**
 * A column's cards: fills the column's height and wraps into extra sub-columns instead of
 * scrolling vertically. Give each card [BOARD_CARD_WIDTH].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoardCardFlow(content: @Composable FlowColumnScope.() -> Unit) {
    FlowColumn(
        modifier = Modifier
            .fillMaxHeight()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}
