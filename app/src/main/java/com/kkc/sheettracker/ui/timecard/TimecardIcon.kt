package com.kkc.sheettracker.ui.timecard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val TimecardIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "TimecardIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Card outline
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5f, 2f)
            horizontalLineTo(19f)
            arcTo(2f, 2f, 0f, false, true, 21f, 4f)
            verticalLineTo(20f)
            arcTo(2f, 2f, 0f, false, true, 19f, 22f)
            horizontalLineTo(5f)
            arcTo(2f, 2f, 0f, false, true, 3f, 20f)
            verticalLineTo(4f)
            arcTo(2f, 2f, 0f, false, true, 5f, 2f)
            close()
        }
        // Top swipe bar
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 4f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(6.5f, 6.5f)
            lineTo(15.5f, 6.5f)
        }
        // 3×3 numpad dots
        for ((cx, cy) in listOf(
            6.5f to 12f, 9.5f to 12f, 12.5f to 12f,
            6.5f to 15f, 9.5f to 15f, 12.5f to 15f,
            6.5f to 18f, 9.5f to 18f, 12.5f to 18f
        )) {
            path(fill = SolidColor(Color.Black)) {
                moveTo(cx - 1f, cy)
                arcTo(1f, 1f, 0f, false, true, cx + 1f, cy)
                arcTo(1f, 1f, 0f, false, true, cx - 1f, cy)
                close()
            }
        }
        // Action button column (larger dots)
        for (cy in listOf(12f, 18f)) {
            path(fill = SolidColor(Color.Black)) {
                moveTo(15f, cy)
                arcTo(1.5f, 1.5f, 0f, false, true, 18f, cy)
                arcTo(1.5f, 1.5f, 0f, false, true, 15f, cy)
                close()
            }
        }
    }.build()
}
