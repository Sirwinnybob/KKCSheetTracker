package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.kkc.sheettracker.data.models.SheetStatus

data class NavBarSearchDecoration(
    val searchTextValue: TextFieldValue,
    val onSearchTextChange: (TextFieldValue) -> Unit,
    val onGo: () -> Unit,
    val isPartsEnabled: Boolean,
    val onParts: () -> Unit,
    val contextLine: String,
    val placeholder: String = "Cabinet #",
    val showParts: Boolean = true,
    val onScan: (() -> Unit)? = null
)

data class NavBarCncDecoration(
    val currentPage: Int,
    val totalPages: Int,
    val sheetStatus: SheetStatus,
    val onPrevPage: () -> Unit,
    val onNextPage: () -> Unit,
    val onOpenToc: () -> Unit,
    val onToggleSkip: () -> Unit,
    val onToggleComplete: () -> Unit,
    val onOpenSearch: () -> Unit,
    val onToggleRenested: () -> Unit
)

data class NavBarSpecialtyDecoration(
    val onAddItem: () -> Unit
)

/**
 * Pen/markup controls rendered as a sliding decoration tab in the minimized nav bar.
 * Carries the real toolbar content (RowScope-scoped) so it can slide in/out horizontally
 * against the search tab instead of forcing a separate full-bar swap via [extendedControls].
 */
data class NavBarPenDecoration(
    val content: @Composable RowScope.() -> Unit
)

class NavBarDecorationState {
    var searchDecoration: NavBarSearchDecoration? by mutableStateOf(null)
    var cncDecoration: NavBarCncDecoration? by mutableStateOf(null)
    var specialtyDecoration: NavBarSpecialtyDecoration? by mutableStateOf(null)
    var penDecoration: NavBarPenDecoration? by mutableStateOf(null)
    var extendedControls: (@Composable RowScope.() -> Unit)? by mutableStateOf(null)
}

val LocalNavBarDecoration = compositionLocalOf { NavBarDecorationState() }
