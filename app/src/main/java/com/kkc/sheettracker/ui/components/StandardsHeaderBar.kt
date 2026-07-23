package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Small floating header row hosting the Settings and Standards entry points. Both destinations
 * were removed from [AppBottomNavBar] (see [NavDestination.SETTINGS] / [NavDestination.STANDARDS]
 * filtering at each call site) and relocated here so they no longer compete for space with the
 * frequently-used bottom tabs, while still keeping full tab semantics (own back stack, own
 * NavHostController) via [com.kkc.sheettracker.navigation.NavigationCoordinator.navigateTopLevel].
 */
@Composable
fun StandardsHeaderBar(
    onSettingsClick: () -> Unit,
    onStandardsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.padding(8.dp)) {
        IconButton(onClick = onStandardsClick) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Standards")
        }
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}
