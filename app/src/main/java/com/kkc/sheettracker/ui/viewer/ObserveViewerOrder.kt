package com.kkc.sheettracker.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.kkc.sheettracker.data.models.Material

/** Shared by the full viewer and network-free device fixtures. No catalog update chooses a resume page. */
@Composable
internal fun ObserveViewerOrder(material: Material?, order: ViewerOrder, currentPage: Int,
    previousPages: List<Int>, onOrder: (List<Int>, Int) -> Unit) {
    LaunchedEffect(material, order) {
        if (material == null) return@LaunchedEffect
        val pages = (order as? ViewerOrder.Mix)?.pages ?: material.visibleSheetPages()
        val fallback = currentPage.coerceIn(1, material.pageCount.coerceAtLeast(1))
        onOrder(pages, retainedViewerPage(currentPage, previousPages, pages, fallback))
    }
}
