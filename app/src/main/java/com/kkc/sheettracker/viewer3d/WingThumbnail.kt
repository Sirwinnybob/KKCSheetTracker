package com.kkc.sheettracker.viewer3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Native, resource-free preview: composing/scrolling it never creates a WebView or server. */
@Composable
fun WingThumbnail(
    scene: WingScenePayload,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val projection = remember(scene) { projectWingThumbnail(scene) }
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "Open 3D Cabinet ${scene.cabinet}" }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val extentX = (projection.bounds.maxX - projection.bounds.minX).coerceAtLeast(0.001)
            val extentY = (projection.bounds.maxY - projection.bounds.minY).coerceAtLeast(0.001)
            projection.faces.forEachIndexed { index, face ->
                val left = ((face.points.minOf { it.x } - projection.bounds.minX) / extentX * size.width).toFloat()
                val right = ((face.points.maxOf { it.x } - projection.bounds.minX) / extentX * size.width).toFloat()
                val top = ((projection.bounds.maxY - face.points.maxOf { it.y }) / extentY * size.height).toFloat()
                val bottom = ((projection.bounds.maxY - face.points.minOf { it.y }) / extentY * size.height).toFloat()
                val fill = if (face.materialId == null) Color(0xFFE2D5C2) else Color(0xFFC6A77B)
                drawRect(fill.copy(alpha = (0.88f - index * 0.04f).coerceAtLeast(0.28f)), topLeft = androidx.compose.ui.geometry.Offset(left, top), size = androidx.compose.ui.geometry.Size(right - left, bottom - top))
                drawRect(Color(0xFF5F4931), topLeft = androidx.compose.ui.geometry.Offset(left, top), size = androidx.compose.ui.geometry.Size(right - left, bottom - top), style = Stroke(1.dp.toPx()))
            }
        }
    }
}
