package com.kkc.sheettracker.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Search state pushed from AssemblyViewerScreen up into the floating nav bar.
 * Uses TextFieldValue so cursor position survives the SideEffect round-trip.
 */
data class NavBarSearchDecoration(
    val searchTextValue: TextFieldValue,
    val onSearchTextChange: (TextFieldValue) -> Unit,
    val onGo: () -> Unit,
    val isPartsEnabled: Boolean,
    val onParts: () -> Unit,
    val contextLine: String
)

/** Mutable holder — NavGraph creates one instance and provides it via [LocalNavBarDecoration]. */
class NavBarDecorationState {
    var searchDecoration: NavBarSearchDecoration? by mutableStateOf(null)
}

/**
 * CompositionLocal so AssemblyViewerScreen (deep in the content) can write to the
 * decoration without parameter-drilling through the navigation graph.
 */
val LocalNavBarDecoration = compositionLocalOf { NavBarDecorationState() }
