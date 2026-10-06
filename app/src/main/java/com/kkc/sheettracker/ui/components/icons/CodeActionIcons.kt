package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

// Manage code: a duotone code sheet with a slim, solid wrench.
val ActionManageCodeIcon: ImageVector by lazy {
    kkcIcon("ActionManageCode") {
        solid(DUOTONE) {
            moveTo(4.5f, 3f)
            horizontalLineTo(13f)
            lineTo(17f, 7f)
            verticalLineTo(20.5f)
            horizontalLineTo(4.5f)
            close()
        }
        line {
            // Leave the lower-right outline open behind the wrench.
            moveTo(4.5f, 20.5f)
            verticalLineTo(3f)
            horizontalLineTo(13f)
            lineTo(17f, 7f)
            verticalLineTo(9f)
            moveTo(13f, 3f)
            verticalLineTo(7f)
            horizontalLineTo(17f)
            moveTo(4.5f, 20.5f)
            horizontalLineTo(8.5f)
        }
        line(width = 1.5f) {
            moveTo(7.5f, 8f); horizontalLineTo(10.5f)
            moveTo(7.5f, 11f); horizontalLineTo(10f)
            moveTo(7.5f, 14f); horizontalLineTo(8.5f)
        }
        solid {
            moveTo(19.5f, 8.5f)
            curveTo(17.5f, 8f, 15.5f, 10f, 16f, 12f)
            lineTo(10.5f, 17.5f)
            quadTo(9.75f, 18.25f, 10.5f, 19f)
            lineTo(11f, 19.5f)
            quadTo(11.75f, 20.25f, 12.5f, 19.5f)
            lineTo(18f, 14f)
            curveTo(20f, 14.5f, 22f, 12.5f, 21.5f, 10.5f)
            lineTo(19f, 13f)
            lineTo(17f, 11f)
            close()
        }
    }
}
