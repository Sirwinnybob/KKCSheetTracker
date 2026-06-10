package com.kkc.sheettracker.ui.components

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
    val contextLine: String
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
    val onOpenSearch: () -> Unit
)

data class NavBarSpecialtyDecoration(
    val onAddItem: () -> Unit
)

class NavBarDecorationState {
    var searchDecoration: NavBarSearchDecoration? by mutableStateOf(null)
    var cncDecoration: NavBarCncDecoration? by mutableStateOf(null)
    var specialtyDecoration: NavBarSpecialtyDecoration? by mutableStateOf(null)
}

val LocalNavBarDecoration = compositionLocalOf { NavBarDecorationState() }
