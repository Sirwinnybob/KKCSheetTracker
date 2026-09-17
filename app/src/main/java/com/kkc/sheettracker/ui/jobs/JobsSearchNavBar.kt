package com.kkc.sheettracker.ui.jobs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.input.TextFieldValue
import com.kkc.sheettracker.ui.components.NavBarDecorationState
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration

@Composable
internal fun JobsSearchNavBar(
    navBarDeco: NavBarDecorationState,
    ownerId: String,
    active: Boolean,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onGo: () -> Unit,
) {
    val currentOnQueryChange = rememberUpdatedState(onQueryChange)
    val currentOnGo = rememberUpdatedState(onGo)
    // SideEffect runs outside composition: callbacks allocated there are new on every pass.
    // Publishing those callbacks invalidates the parent that reads the decoration, feeding
    // another publication. Retain the decoration until its visible data actually changes.
    val decoration = remember(query) {
        NavBarSearchDecoration(
            searchTextValue = query,
            onSearchTextChange = { currentOnQueryChange.value(it) },
            onGo = { currentOnGo.value() },
            isPartsEnabled = false,
            onParts = {},
            contextLine = if (query.text.isNotBlank()) "Filtering jobs by \"${query.text}\"" else "",
            placeholder = "Search jobs...",
            showParts = false,
            onScan = null,
        )
    }
    SideEffect {
        if (active) {
            navBarDeco.owner = ownerId
            navBarDeco.searchDecoration = decoration
        } else if (navBarDeco.owner == ownerId) {
            navBarDeco.searchDecoration = null
            navBarDeco.keepSearchDeco = false
            navBarDeco.owner = ""
        }
    }
    DisposableEffect(navBarDeco, ownerId) {
        onDispose {
            if (navBarDeco.owner == ownerId) {
                navBarDeco.searchDecoration = null
                navBarDeco.keepSearchDeco = false
                navBarDeco.owner = ""
            }
        }
    }
}
