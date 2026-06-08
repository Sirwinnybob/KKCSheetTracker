package com.kkc.sheettracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.theme.KKCShapeTokens
import com.kkc.sheettracker.ui.theme.KKCSpacing
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

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
    hazeState: HazeState? = null,
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
                    .padding(
                        start = KKCSpacing.floatingNavMinSideMargin,
                        end = KKCSpacing.floatingNavMinSideMargin,
                        bottom = KKCSpacing.floatingNavBottomGap
                    )
            ) {
                // Full pill shape — matches Apple Photos reference
                val navShape = KKCShapeTokens.pill
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(elevation = 4.dp, shape = navShape, clip = false)
                        .clip(navShape)
                        .then(
                            if (hazeState != null)
                                Modifier.hazeEffect(
                                    hazeState,
                                    style = HazeDefaults.style(
                                        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                        blurRadius = 25.dp
                                    )
                                )
                            else
                                Modifier.background(MaterialTheme.colorScheme.surface)
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            shape = navShape
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        destinations.forEach { dest ->
                            // Calculator item is injected just before the HOURS slot
                            if (dest == NavDestination.HOURS) {
                                FullNavItem(
                                    selected = isCalculatorOpen,
                                    onClick = onCalculatorClick,
                                    icon = if (isCalculatorOpen) Icons.Filled.Calculate else Icons.Outlined.Calculate,
                                    label = "Calc"
                                )
                            }
                            val selected = dest == currentDestination
                            FullNavItem(
                                selected = selected,
                                onClick = { onNavigate(dest) },
                                icon = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                label = dest.label,
                                badgeCount = if (dest == NavDestination.SUPPLY) supplyNotificationCount else 0
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
                supplyNotificationCount = supplyNotificationCount,
                hazeState = hazeState
            )
        }
    }
}

/**
 * Single item for the full (expanded) nav bar.
 * Large pill indicator behind icon+label together — matches the Apple Photos reference.
 */
@Composable
private fun FullNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    badgeCount: Int = 0
) {
    val indicatorShape = MaterialTheme.shapes.extraLarge
    val selectedTint = MaterialTheme.colorScheme.primary
    val unselectedTint = MaterialTheme.colorScheme.onSurfaceVariant
    val indicatorColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier
            .clip(indicatorShape)
            .background(
                color = if (selected) indicatorColor else Color.Transparent,
                shape = indicatorShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (badgeCount > 0) {
            BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(22.dp),
                    tint = if (selected) selectedTint else unselectedTint
                )
            }
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(22.dp),
                tint = if (selected) selectedTint else unselectedTint
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) selectedTint else unselectedTint
        )
    }
}

@Composable
private fun MinimizedNavBar(
    currentDestination: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    onCalculatorClick: () -> Unit,
    isCalculatorOpen: Boolean,
    destinations: List<NavDestination>,
    supplyNotificationCount: Int,
    hazeState: HazeState? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = KKCSpacing.floatingNavMinSideMargin,
                end = KKCSpacing.floatingNavMinSideMargin,
                bottom = KKCSpacing.floatingNavBottomGap
            )
    ) {
        val minNavShape = KKCShapeTokens.pill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 3.dp, shape = minNavShape, clip = false)
                .clip(minNavShape)
                .then(
                    if (hazeState != null)
                        Modifier.hazeEffect(
                            hazeState,
                            style = HazeDefaults.style(
                                backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                blurRadius = 25.dp
                            )
                        )
                    else
                        Modifier.background(MaterialTheme.colorScheme.surface)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    shape = minNavShape
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(horizontal = KKCSpacing.navBarHorizontal, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val indicatorShape = MaterialTheme.shapes.medium
                val indicatorColor = MaterialTheme.colorScheme.surfaceVariant

                destinations.forEach { dest ->
                    if (dest == NavDestination.HOURS) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(indicatorShape)
                                .background(
                                    color = if (isCalculatorOpen) indicatorColor else Color.Transparent,
                                    shape = indicatorShape
                                )
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
                            .clip(indicatorShape)
                            .background(
                                color = if (selected) indicatorColor else Color.Transparent,
                                shape = indicatorShape
                            )
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
