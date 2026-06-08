package com.kkc.sheettracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.theme.KKCShapeTokens
import com.kkc.sheettracker.ui.theme.KKCSpacing

enum class NavDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    JOBS("jobs", "Jobs", Icons.AutoMirrored.Filled.List, Icons.AutoMirrored.Outlined.List),
    SEARCH("search", "Search", Icons.Filled.Search, Icons.Outlined.Search),
    HOURS("hours", "Hours", Icons.Filled.AccessTime, Icons.Outlined.AccessTime),
    SUPPLY("supply", "Supply", Icons.Filled.ShoppingCart, Icons.Outlined.ShoppingCart),
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
    supplyNotificationCount: Int = 0,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = !minimized,
            enter = slideInVertically(tween(200)) { it },
            exit = slideOutVertically(tween(200)) { it },
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = KKCSpacing.floatingNavSideMargin, end = KKCSpacing.floatingNavSideMargin, bottom = KKCSpacing.floatingNavBottomGap)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 18.dp,
                    tonalElevation = 4.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets(0)
                    ) {
                        destinations.forEach { dest ->
                            if (dest == NavDestination.HOURS) {
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
                                    val iconContent = @Composable {
                                        Icon(
                                            if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    }
                                    if (dest == NavDestination.SUPPLY && supplyNotificationCount > 0) {
                                        BadgedBox(badge = { Badge { Text(supplyNotificationCount.toString()) } }) {
                                            iconContent()
                                        }
                                    } else {
                                        iconContent()
                                    }
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
            }
        }

        AnimatedVisibility(
            visible = minimized,
            enter = slideInVertically(tween(200)) { it },
            exit = slideOutVertically(tween(200)) { it },
            modifier = Modifier.fillMaxWidth()
        ) {
            MinimizedNavBar(
                currentDestination = currentDestination,
                onNavigate = onNavigate,
                onCalculatorClick = onCalculatorClick,
                isCalculatorOpen = isCalculatorOpen,
                destinations = destinations,
                supplyNotificationCount = supplyNotificationCount
            )
        }
    }
}

@Composable
private fun MinimizedNavBar(
    currentDestination: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    onCalculatorClick: () -> Unit,
    isCalculatorOpen: Boolean,
    destinations: List<NavDestination>,
    supplyNotificationCount: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = KKCSpacing.floatingNavMinSideMargin, end = KKCSpacing.floatingNavMinSideMargin, bottom = KKCSpacing.floatingNavBottomGap)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = KKCShapeTokens.pill,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 14.dp,
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(horizontal = KKCSpacing.navBarHorizontal),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEach { dest ->
                    if (dest == NavDestination.HOURS) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clickable { onCalculatorClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isCalculatorOpen) Icons.Filled.Calculate else Icons.Outlined.Calculate,
                                contentDescription = "Calculator",
                                tint = if (isCalculatorOpen) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    val selected = dest == currentDestination
                    val iconContent = @Composable {
                        Icon(
                            if (selected) dest.selectedIcon else dest.unselectedIcon,
                            contentDescription = dest.label,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable { onNavigate(dest) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (dest == NavDestination.SUPPLY && supplyNotificationCount > 0) {
                            BadgedBox(badge = { Badge { Text(supplyNotificationCount.toString()) } }) {
                                iconContent()
                            }
                        } else {
                            iconContent()
                        }
                    }
                }
            }
        }
    }
}
