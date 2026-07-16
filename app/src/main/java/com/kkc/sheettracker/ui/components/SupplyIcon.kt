package com.kkc.sheettracker.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val SupplyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SupplyIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Forklift truck body - cab and wheels area (single filled path)
        path(fill = SolidColor(Color.Black)) {
            // Main body outline
            moveTo(6f, 4f)
            verticalLineTo(11f)
            horizontalLineTo(4f)
            // Curved corner - approximate with small arc
            arcTo(1f, 1f, 0f, false, false, 2.89f, 11.89f)
            lineTo(2f, 13f)
            verticalLineTo(17f)
            // Left wheel (semi-circle)
            arcTo(3f, 3f, 0f, false, false, 5f, 20f)
            arcTo(3f, 3f, 0f, false, false, 8f, 17f)
            horizontalLineTo(10f)
            // Right wheel (semi-circle)
            arcTo(3f, 3f, 0f, false, false, 13f, 20f)
            arcTo(3f, 3f, 0f, false, false, 16f, 17f)
            verticalLineTo(13f)
            horizontalLineTo(12f)
            horizontalLineTo(6f)
            close()
        }
        // Fork prongs frame (right side vertical rectangle)
        path(fill = SolidColor(Color.Black)) {
            moveTo(17f, 5f)
            verticalLineTo(19f)
            horizontalLineTo(22f)
            verticalLineTo(17.5f)
            horizontalLineTo(18.5f)
            verticalLineTo(5f)
            horizontalLineTo(17f)
            close()
        }
        // Forklift forks and mast (thick stroke lines)
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Mast (vertical lift support) - from y=4 to y=13
            moveTo(7.5f, 4f)
            verticalLineTo(13f)
            // Forks (horizontal prongs) - one at y=11, one at y=13
            moveTo(4f, 11f)
            horizontalLineTo(14.5f)
            moveTo(4f, 13f)
            horizontalLineTo(14.5f)
        }
        // Left wheel circle
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 15.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 6.5f, 17f)
            arcTo(1.5f, 1.5f, 0f, false, true, 5f, 18.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 3.5f, 17f)
            arcTo(1.5f, 1.5f, 0f, false, true, 5f, 15.5f)
            close()
        }
        // Right wheel circle
        path(fill = SolidColor(Color.Black)) {
            moveTo(13f, 15.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 14.5f, 17f)
            arcTo(1.5f, 1.5f, 0f, false, true, 13f, 18.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 11.5f, 17f)
            arcTo(1.5f, 1.5f, 0f, false, true, 13f, 15.5f)
            close()
        }
    }.build()
}