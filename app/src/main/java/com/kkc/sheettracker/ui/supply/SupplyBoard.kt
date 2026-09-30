package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Scroll state for the supply category board. The board is a plain (non-lazy) scrolling row, so
 * every column is composed (in the background, a column per frame) and measured before the user
 * pans to it: column widths grow with their card count (cards wrap into extra sub-columns), and a
 * lazy row only learned a width once a column scrolled into view -- composing it mid-pan (stutter).
 */
@Stable
class SupplyBoardState internal constructor(
    val scroll: ScrollState,
    /** Gap between the viewport's left edge and a column lined up at it. */
    private val edgePx: Float
) {
    /** Column key -> left edge and width (px) in the board row's content coordinates. */
    internal val columns = mutableStateMapOf<String, Pair<Int, Int>>()

    /** Column keys in board order. */
    var keys: List<String> by mutableStateOf(emptyList())
        internal set

    val isScrollInProgress: Boolean get() = scroll.isScrollInProgress

    /** Width of the board's viewport, px. */
    var viewportPx by mutableIntStateOf(0)
        internal set

    internal fun updateKeys(newKeys: List<String>) {
        keys = newKeys
        columns.keys.retainAll(newKeys.toSet())
    }

    /** Left edges of the columns built so far (they are built front to back). */
    private fun starts(): List<Int> {
        val out = ArrayList<Int>(keys.size)
        for (key in keys) out += columns[key]?.first ?: break
        return out
    }

    /** Scroll value that lines column [start] up at the left edge (clamped to the scroll range). */
    private fun anchorFor(start: Int): Float =
        (start - edgePx).coerceIn(0f, scroll.maxValue.toFloat())

    /**
     * Fractional index into [keys] of the column at the left edge: 2.4 means column 2 is 40% of
     * the way to scrolling out and column 3 is taking its place. Null until columns are measured.
     */
    fun position(): Float? {
        val s = starts()
        if (s.isEmpty()) return null
        val x = scroll.value + edgePx
        if (x <= s.first()) return 0f
        val i = s.indexOfLast { it <= x }
        if (i >= s.lastIndex) return s.lastIndex.toFloat()
        val span = (s[i + 1] - s[i]).coerceAtLeast(1)
        return i + ((x - s[i]) / span).coerceIn(0f, 1f)
    }

    /** Key of the column that is mostly at the left edge. */
    fun activeKey(): String? = position()?.let { keys.getOrNull(it.roundToInt()) }

    suspend fun scrollToColumn(key: String, animate: Boolean) {
        // The board page may have just been composed, or the column not built yet; wait (briefly).
        val start = withTimeoutOrNull(3_000) {
            snapshotFlow { columns[key]?.first }.filterNotNull().first()
        } ?: return
        val target = anchorFor(start).roundToInt()
        if (animate) scroll.animateScrollTo(target) else scroll.scrollTo(target)
    }
}

@Composable
fun rememberSupplyBoardState(): SupplyBoardState {
    val scroll = rememberScrollState()
    val edgePx = with(LocalDensity.current) { 16.dp.toPx() }
    return remember(scroll, edgePx) { SupplyBoardState(scroll, edgePx) }
}



/**
 * Sits between the board and the page pager.
 *
 * - A swipe that starts with the board already at its left edge may carry on into the pager (to
 *   TO ORDER etc.); one that starts mid-board stops at the edge, momentum included, so a fast pan
 *   never overshoots into the next page -- it takes another swipe.
 * - The pager discards the fling velocity of content scrolled inside it and settles by position
 *   alone, so a swipe that began on the board needed dragging past half way to change page. When
 *   such a swipe ends with the pager part way over, this hands the flick to the pager: a flick
 *   finishes the page change in its direction, a slow release settles to the nearest page.
 */
@Composable
fun rememberSupplyBoardEdgeConnection(
    board: SupplyBoardState,
    pagerState: PagerState
): NestedScrollConnection {
    val flickPx = with(LocalDensity.current) { 400.dp.toPx() }
    return remember(board, pagerState, flickPx) {
        object : NestedScrollConnection {
            private var inGesture = false
            private var startedAtEdge = false

            private fun pagerMoved() = abs(pagerState.currentPageOffsetFraction) > 0.001f

            private fun passThrough() = startedAtEdge || pagerMoved()

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && !inGesture) {
                    inGesture = true
                    startedAtEdge = !board.scroll.canScrollBackward
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (passThrough()) Offset.Zero else available.copy(y = 0f)

            override suspend fun onPreFling(available: Velocity): Velocity {
                inGesture = false
                if (!pagerMoved()) return Velocity.Zero
                // Pager part way over: settle it here with the flick's velocity, and keep the
                // velocity from the board so it doesn't also scroll the board.
                val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
                val target = when {
                    available.x > flickPx -> floor(position) // finger moving right: previous page
                    available.x < -flickPx -> ceil(position)
                    else -> position.roundToInt().toFloat()
                }.toInt().coerceIn(0, pagerState.pageCount - 1)
                pagerState.animateScrollToPage(target)
                return available.copy(y = 0f)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (passThrough()) Velocity.Zero else available.copy(y = 0f)
        }
    }
}
