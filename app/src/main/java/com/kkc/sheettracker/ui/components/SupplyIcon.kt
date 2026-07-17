package com.kkc.sheettracker.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
        path(
            fill = SolidColor(Color.Black),
            stroke = null
        ) {
            // Main forklift body + cab
            moveTo(6f, 4f)
            verticalLineTo(11f)
            horizontalLineTo(4f)
            curveTo(2.89f, 11f, 2f, 11.89f, 2f, 13f)
            verticalLineTo(17f)
            arcTo(3f, 3f, 0f, false, false, 5f, 20f)
            arcTo(3f, 3f, 0f, false, false, 8f, 17f)
            horizontalLineTo(10f)
            arcTo(3f, 3f, 0f, false, false, 13f, 20f)
            arcTo(3f, 3f, 0f, false, false, 16f, 17f)
            verticalLineTo(13f)
            lineTo(12f, 4f)
            horizontalLineTo(6f)
            close()

            // Mast / vertical frame
            moveTo(17f, 5f)
            verticalLineTo(19f)
            horizontalLineTo(22f)
            verticalLineTo(17.5f)
            horizontalLineTo(18.5f)
            verticalLineTo(5f)
            horizontalLineTo(17f)
            close()

            // Fork prongs
            moveTo(7.5f, 5.5f)
            horizontalLineTo(11.2f)
            lineTo(14.5f, 13f)
            horizontalLineTo(7.5f)
            verticalLineTo(5.5f)
            close()

            // Left wheel
            moveTo(5f, 15.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 6.5f, 17f)
            arcTo(1.5f, 1.5f, 0f, false, true, 5f, 18.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 3.5f, 17f)
            arcTo(1.5f, 1.5f, 0f, false, true, 5f, 15.5f)
            close()

            // Right wheel
            moveTo(13f, 15.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 14.5f, 17f)
            arcTo(1.5f, 1.5f, 0f, false, true, 13f, 18.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 11.5f, 17f)
            arcTo(1.5f, 1.5f, 0f, false, true, 13f, 15.5f)
            close()
        }
    }.build()
}