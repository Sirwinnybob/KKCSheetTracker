package com.kkc.sheettracker.ui.settings

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Layout rules for the Settings shell. Tablets rotate freely: portrait 824dp wide, landscape 1318dp.

/** Landscape cap so the rail still fits above the floating navbar; portrait tiles stay square. */
internal val MODE_TILE_MAX_HEIGHT: Dp = 168.dp
internal const val WIDE_CHIPS_MIN_WIDTH_DP = 1000f

/** Text fields stop stretching here so landscape panes don't get 1000dp-wide inputs. */
internal val MAX_FIELD_WIDTH: Dp = 640.dp

internal fun showWideChips(widthDp: Float): Boolean = widthDp >= WIDE_CHIPS_MIN_WIDTH_DP

internal fun modeTileHeight(tileWidth: Dp): Dp = minOf(tileWidth, MODE_TILE_MAX_HEIGHT)
