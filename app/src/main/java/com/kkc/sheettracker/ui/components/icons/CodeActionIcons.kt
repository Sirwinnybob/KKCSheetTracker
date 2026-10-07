package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder

// Manage code: a code page with a solid round badge holding a cut-out open-end wrench.
val ActionManageCodeIcon: ImageVector by lazy {
    kkcIcon("ActionManageCode") {
        val page: PathBuilder.() -> Unit = {
            moveTo(5.5f, 2.5f)
            horizontalLineTo(13f)
            lineTo(17f, 6.5f)
            verticalLineTo(20f)
            arcTo(1.5f, 1.5f, 0f, false, true, 15.5f, 21.5f)
            horizontalLineTo(5.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 4f, 20f)
            verticalLineTo(4f)
            arcTo(1.5f, 1.5f, 0f, false, true, 5.5f, 2.5f)
            close()
        }
        solid(DUOTONE, pathBuilder = page)
        // Page outline stops short of the badge.
        line(width = 1.5f) {
            moveTo(17f, 12.5f)
            verticalLineTo(6.5f)
            lineTo(13f, 2.5f)
            horizontalLineTo(5.5f)
            arcTo(1.5f, 1.5f, 0f, false, false, 4f, 4f)
            verticalLineTo(20f)
            arcTo(1.5f, 1.5f, 0f, false, false, 5.5f, 21.5f)
            horizontalLineTo(12.5f)
        }
        // folded corner
        block(width = 1f) { moveTo(13f, 2.5f); verticalLineTo(6.5f); horizontalLineTo(17f); close() }
        // code lines
        line(width = 1.5f) {
            moveTo(7f, 7.5f); horizontalLineTo(10.5f)
            moveTo(7f, 10.5f); horizontalLineTo(9.5f)
        }
        // badge with the wrench knocked out
        solid(fillType = PathFillType.EvenOdd) {
            circle(17.5f, 17.5f, 4.5f)
            moveTo(15.89f, 18.19f)
            arcTo(1.75f, 1.75f, 0f, false, true, 18.22f, 15.9f)
            lineTo(17.43f, 16.69f)
            arcTo(0.62f, 0.62f, 0f, false, false, 18.31f, 17.57f)
            lineTo(19.1f, 16.78f)
            arcTo(1.75f, 1.75f, 0f, false, true, 16.81f, 19.11f)
            lineTo(14.71f, 21.21f)
            arcTo(0.65f, 0.65f, 0f, false, true, 13.79f, 20.29f)
            close()
        }
    }
}
