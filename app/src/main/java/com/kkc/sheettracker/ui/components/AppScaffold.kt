package com.kkc.sheettracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class NavDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    JOBS("jobs", "Jobs", Icons.Filled.List, Icons.Outlined.List),
    SEARCH("search", "Search", Icons.Filled.Search, Icons.Outlined.Search),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@Composable
fun AppBottomNavBar(
    currentDestination: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    onCalculatorClick: () -> Unit,
    isCalculatorOpen: Boolean,
    minimized: Boolean,
    destinations: List<NavDestination> = NavDestination.entries,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = !minimized,
        enter = slideInVertically(tween(200)) { it },
        exit = slideOutVertically(tween(200)) { it },
        modifier = modifier
    ) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            destinations.forEach { dest ->
                if (dest == NavDestination.SETTINGS) {
                    NavigationBarItem(
                        selected = isCalculatorOpen,
                        onClick = onCalculatorClick,
                        icon = {
                            Icon(
                                if (isCalculatorOpen) Icons.Filled.Calculate else Icons.Outlined.Calculate,
                                contentDescription = "Calculator"
                            )
                        },
                        label = { Text("Calc", style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
                val selected = dest == currentDestination
                NavigationBarItem(
                    selected = selected,
                    onClick = { onNavigate(dest) },
                    icon = {
                        Icon(
                            if (selected) dest.selectedIcon else dest.unselectedIcon,
                            contentDescription = dest.label
                        )
                    },
                    label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    }

    if (minimized) {
        MinimizedNavBar(
            currentDestination = currentDestination,
            onNavigate = onNavigate,
            onCalculatorClick = onCalculatorClick,
            isCalculatorOpen = isCalculatorOpen,
            destinations = destinations
        )
    }
}

@Composable
private fun MinimizedNavBar(
    currentDestination: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    onCalculatorClick: () -> Unit,
    isCalculatorOpen: Boolean,
    destinations: List<NavDestination>
) {
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(40.dp)
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            destinations.forEach { dest ->
                if (dest == NavDestination.SETTINGS) {
                    Icon(
                        if (isCalculatorOpen) Icons.Filled.Calculate else Icons.Outlined.Calculate,
                        contentDescription = "Calculator",
                        tint = if (isCalculatorOpen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onCalculatorClick() }
                    )
                }
                val selected = dest == currentDestination
                Icon(
                    if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = dest.label,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onNavigate(dest) }
                )
            }
        }
    }
}
