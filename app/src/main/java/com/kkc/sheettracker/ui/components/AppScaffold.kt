package com.kkc.sheettracker.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.shape.CircleShape
import com.kkc.sheettracker.ui.timecard.BgPickerSheet
import com.kkc.sheettracker.ui.timecard.TimecardIcon
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.UnfoldMore
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    TIMECARD("timecard", "Timeclock", TimecardIcon, TimecardIcon),
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
    searchDecoration: NavBarSearchDecoration? = null,
    cncDecoration: NavBarCncDecoration? = null,
    specialtyDecoration: NavBarSpecialtyDecoration? = null,
    modifier: Modifier = Modifier
) {
    var showBgPicker by remember { mutableStateOf(false) }
    if (showBgPicker) {
        BgPickerSheet(onDismiss = { showBgPicker = false })
    }

    Box(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = !minimized,
            enter = slideInVertically(tween(260)) { it },
            exit = slideOutVertically(tween(260)) { it },
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = navShape,
                    color = if (hazeState != null) Color.Transparent else MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                    tonalElevation = 0.dp
                ) {
                    val fullHazeSurface = MaterialTheme.colorScheme.surface
                    val fullHazeModifier = remember(hazeState, fullHazeSurface) {
                        if (hazeState != null)
                            Modifier.hazeEffect(
                                hazeState,
                                style = HazeDefaults.style(
                                    backgroundColor = fullHazeSurface.copy(alpha = 0.88f),
                                    blurRadius = 25.dp
                                )
                            )
                        else Modifier
                    }
                    Box(modifier = Modifier.fillMaxWidth().then(fullHazeModifier)) {
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
                                badgeCount = if (dest == NavDestination.SUPPLY) supplyNotificationCount else 0,
                                showEditOverlay = dest == NavDestination.TIMECARD && selected,
                                onEditClick = { showBgPicker = true }
                            )
                        }
                    }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = minimized,
            enter = slideInVertically(tween(260)) { it },
            exit = slideOutVertically(tween(260)) { it },
            modifier = Modifier.fillMaxWidth()
        ) {
            MinimizedNavBar(
                currentDestination = currentDestination,
                onNavigate = onNavigate,
                onCalculatorClick = onCalculatorClick,
                isCalculatorOpen = isCalculatorOpen,
                destinations = destinations,
                supplyNotificationCount = supplyNotificationCount,
                hazeState = hazeState,
                searchDecoration = searchDecoration,
                cncDecoration = cncDecoration,
                specialtyDecoration = specialtyDecoration,
                onTimeclockBgEdit = { showBgPicker = true }
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
    badgeCount: Int = 0,
    showEditOverlay: Boolean = false,
    onEditClick: () -> Unit = {}
) {
    val indicatorShape = MaterialTheme.shapes.extraLarge
    val selectedTint = MaterialTheme.colorScheme.primary
    val unselectedTint = MaterialTheme.colorScheme.onSurfaceVariant
    val indicatorColor = MaterialTheme.colorScheme.surfaceVariant

    Box {
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
        if (showEditOverlay) {
            NavEditBadge(onClick = onEditClick, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun NavEditBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.TopEnd
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit background",
                modifier = Modifier.size(10.dp),
                tint = Color.White
            )
        }
    }
}

private enum class NavBarMode { NORMAL, SEARCH, CNC, SPECIALTY }

@Composable
private fun MinimizedNavBar(
    currentDestination: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    onCalculatorClick: () -> Unit,
    isCalculatorOpen: Boolean,
    destinations: List<NavDestination>,
    supplyNotificationCount: Int,
    hazeState: HazeState? = null,
    searchDecoration: NavBarSearchDecoration? = null,
    cncDecoration: NavBarCncDecoration? = null,
    specialtyDecoration: NavBarSpecialtyDecoration? = null,
    onTimeclockBgEdit: () -> Unit = {}
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
        val cornerRadius by animateDpAsState(
            targetValue = if (searchDecoration != null || cncDecoration != null || specialtyDecoration != null) 26.dp else 999.dp,
            animationSpec = tween(286),
            label = "navCorner"
        )
        val minNavShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = minNavShape,
            color = if (hazeState != null) Color.Transparent else MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            tonalElevation = 0.dp
        ) {
            val minHazeSurface = MaterialTheme.colorScheme.surface
            val minHazeModifier = remember(hazeState, minHazeSurface) {
                if (hazeState != null)
                    Modifier.hazeEffect(
                        hazeState,
                        style = HazeDefaults.style(
                            backgroundColor = minHazeSurface.copy(alpha = 0.88f),
                            blurRadius = 25.dp
                        )
                    )
                else Modifier
            }
            Box(modifier = Modifier.fillMaxWidth().then(minHazeModifier)) {
            val navBarMode = when {
                searchDecoration != null -> NavBarMode.SEARCH
                cncDecoration != null -> NavBarMode.CNC
                specialtyDecoration != null -> NavBarMode.SPECIALTY
                else -> NavBarMode.NORMAL
            }
            AnimatedContent(
                targetState = navBarMode,
                transitionSpec = {
                    fadeIn(tween(286)) togetherWith fadeOut(tween(260)) using
                        SizeTransform(clip = false)
                },
                label = "navBarModeContent"
            ) { mode ->
            when (mode) {
            NavBarMode.SEARCH -> {
                searchDecoration?.let { decoration ->
                // ── Search-enhanced layout: search row + divider + compact icon row ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Search row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BasicTextField(
                            value = decoration.searchTextValue,
                            onValueChange = decoration.onSearchTextChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = { decoration.onGo() }
                            ),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (decoration.searchTextValue.text.isEmpty()) {
                                        Text(
                                            "Cabinet #",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        Button(
                            onClick = { decoration.onGo() },
                            shape = MaterialTheme.shapes.extraLarge,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Go", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { decoration.onParts() },
                            enabled = decoration.isPartsEnabled,
                            shape = MaterialTheme.shapes.extraLarge,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Parts", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // Context line below search field (cabinet location info)
                    if (decoration.contextLine.isNotBlank()) {
                        Text(
                            text = decoration.contextLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp, start = 2.dp)
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 7.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    CompactNavIconRow(
                        destinations = destinations,
                        currentDestination = currentDestination,
                        isCalculatorOpen = isCalculatorOpen,
                        supplyNotificationCount = supplyNotificationCount,
                        onNavigate = onNavigate,
                        onCalculatorClick = onCalculatorClick,
                        onTimeclockBgEdit = onTimeclockBgEdit
                    )
                }
                } // let
            }
            NavBarMode.CNC -> {
                cncDecoration?.let { dec ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // Navigation + action controls row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = dec.onPrevPage,
                                enabled = dec.currentPage > 1,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Previous sheet",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            TextButton(
                                onClick = dec.onOpenToc,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Sheet ${dec.currentPage} of ${dec.totalPages}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Default.UnfoldMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            IconButton(
                                onClick = dec.onNextPage,
                                enabled = dec.currentPage < dec.totalPages,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Next sheet",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = dec.onOpenSearch,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search parts",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            val isSkipped = dec.sheetStatus == SheetStatus.SKIPPED
                            Button(
                                onClick = dec.onToggleSkip,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSkipped) KKCThemeColors.statusColors.skipBorder
                                                     else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (isSkipped) Color.White
                                                   else MaterialTheme.colorScheme.onSurface
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = MaterialTheme.shapes.extraLarge
                            ) {
                                Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(if (isSkipped) "Unskip" else "Skip", style = MaterialTheme.typography.labelMedium)
                            }
                            val isComplete = dec.sheetStatus == SheetStatus.COMPLETE ||
                                             dec.sheetStatus == SheetStatus.HAS_BAD_PARTS
                            Button(
                                onClick = dec.onToggleComplete,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isComplete) KKCThemeColors.statusColors.complete
                                                     else MaterialTheme.colorScheme.primary
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = MaterialTheme.shapes.extraLarge
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(if (isComplete) "Done" else "Complete", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 7.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        CompactNavIconRow(
                            destinations = destinations,
                            currentDestination = currentDestination,
                            isCalculatorOpen = isCalculatorOpen,
                            supplyNotificationCount = supplyNotificationCount,
                            onNavigate = onNavigate,
                            onCalculatorClick = onCalculatorClick
                        )
                    }
                }
            }
            NavBarMode.SPECIALTY -> {
                specialtyDecoration?.let { dec ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Button(
                                onClick = dec.onAddItem,
                                shape = MaterialTheme.shapes.extraLarge,
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add Item", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 7.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        CompactNavIconRow(
                            destinations = destinations,
                            currentDestination = currentDestination,
                            isCalculatorOpen = isCalculatorOpen,
                            supplyNotificationCount = supplyNotificationCount,
                            onNavigate = onNavigate,
                            onCalculatorClick = onCalculatorClick
                        )
                    }
                }
            }
            NavBarMode.NORMAL -> {
                // ── Original icon-only layout ──
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
                        Box {
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
                            if (dest == NavDestination.TIMECARD && selected) {
                                NavEditBadge(
                                    onClick = onTimeclockBgEdit,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                )
                            }
                        }
                    }
                }
            }
            } // when
            } // AnimatedContent
            }
        }
    }
}

@Composable
private fun CompactNavIconRow(
    destinations: List<NavDestination>,
    currentDestination: NavDestination,
    isCalculatorOpen: Boolean,
    supplyNotificationCount: Int,
    onNavigate: (NavDestination) -> Unit,
    onCalculatorClick: () -> Unit,
    onTimeclockBgEdit: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KKCSpacing.navBarHorizontal),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val indicatorShape = MaterialTheme.shapes.medium
        val indicatorColor = MaterialTheme.colorScheme.surfaceVariant

        destinations.forEach { dest ->
            if (dest == NavDestination.HOURS) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
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
                        modifier = Modifier.size(18.dp)
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
                    modifier = Modifier.size(18.dp)
                )
            }
            Box {
                Box(
                    modifier = Modifier
                        .size(38.dp)
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
                if (dest == NavDestination.TIMECARD && selected) {
                    NavEditBadge(
                        onClick = onTimeclockBgEdit,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
            }
        }
    }
}

/**
 * Inline clock shown inside the nav bar, directly left of the Settings icon.
 * [compact] = true for the minimized/compact icon-only bar; false for the full label bar.
 * Updates every 30 seconds.
 */
@Composable
private fun NavClockItem(compact: Boolean) {
    val fmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    var timeText by remember { mutableStateOf(fmt.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            timeText = fmt.format(Date())
        }
    }
    Box(
        modifier = Modifier.padding(horizontal = if (compact) 6.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = timeText,
            style = if (compact) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Kept for compatibility — no longer used as an overlay but available if needed. */
@Composable
fun AppStatusClock(modifier: Modifier = Modifier) {
    NavClockItem(compact = false)
}

@Composable
fun TopBarClock(modifier: Modifier = Modifier) {
    val fmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    var timeText by remember { mutableStateOf(fmt.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            timeText = fmt.format(Date())
        }
    }
    Box(
        modifier = modifier.padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = timeText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
