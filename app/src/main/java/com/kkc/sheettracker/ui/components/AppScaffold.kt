package com.kkc.sheettracker.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.ui.theme.KKCSpacing
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.timecard.BgPickerSheet
import com.kkc.sheettracker.ui.timecard.TimecardIcon
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

// ── Shared animation specs ─────────────────────────────────────────────────────
private val NavEasing    = FastOutSlowInEasing
private val NavAnimDp    = tween<Dp>(440,    easing = NavEasing)
private val NavAnimFloat = tween<Float>(440, easing = NavEasing)
private val NavAnimEnter = tween<Float>(340, delayMillis = 90, easing = NavEasing)
private val NavAnimExit  = tween<Float>(280, easing = NavEasing)

// Horizontal decoration-tab slide duration (search ↔ pen ↔ cnc …).
private const val NAV_SLIDE_MS = 440

// Size/shape morphs use a softly-tuned spring so the bar grows and SETTLES
// organically (enlarge/shrink) instead of the mechanical linear-feel of a tween.
// Slight underdamping (0.82) gives a barely-perceptible settle at the end — premium,
// not bouncy. StiffnessMediumLow keeps the morph slow and deliberate.
private val NavSpringDp   = spring<Dp>(
    dampingRatio = 0.82f,
    stiffness    = Spring.StiffnessMediumLow
)
private val NavSpringSize = spring<IntSize>(
    dampingRatio        = 0.82f,
    stiffness           = Spring.StiffnessMediumLow,
    visibilityThreshold = IntSize(1, 1)
)

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
    penDecoration: NavBarPenDecoration? = null,
    extendedControls: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showBgPicker by remember { mutableStateOf(false) }
    if (showBgPicker) {
        BgPickerSheet(onDismiss = { showBgPicker = false })
    }

    // Single always-mounted bar. Full ↔ minimized ↔ extended is one morphing
    // surface — height, corner radius, icon size and labels all settle on the
    // shared spring instead of two surfaces sliding past each other.
    Box(modifier = modifier.fillMaxWidth()) {
        MorphingNavBar(
            currentDestination = currentDestination,
            onNavigate = onNavigate,
            onCalculatorClick = onCalculatorClick,
            isCalculatorOpen = isCalculatorOpen,
            minimized = minimized,
            destinations = destinations,
            supplyNotificationCount = supplyNotificationCount,
            hazeState = hazeState,
            searchDecoration = searchDecoration,
            cncDecoration = cncDecoration,
            specialtyDecoration = specialtyDecoration,
            penDecoration = penDecoration,
            extendedControls = extendedControls,
            onTimeclockBgEdit = { showBgPicker = true }
        )
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

/**
 * Unified nav icon row used in both full and minimized bars.
 * Icons animate size via [iconSize]; labels animate in/out via [showLabels].
 * No composable is swapped — everything morphs in place.
 */
@Composable
private fun MorphingNavIconRow(
    destinations: List<NavDestination>,
    currentDestination: NavDestination,
    isCalculatorOpen: Boolean,
    supplyNotificationCount: Int,
    onNavigate: (NavDestination) -> Unit,
    onCalculatorClick: () -> Unit,
    onTimeclockBgEdit: () -> Unit = {},
    iconSize: Dp,
    showLabels: Boolean
) {
    val hPad by animateDpAsState(if (showLabels) 14.dp else 8.dp, NavSpringDp, label = "navItemHPad")
    val vPad by animateDpAsState(if (showLabels) 8.dp else 6.dp, NavSpringDp, label = "navItemVPad")

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
            // Calculator slot before HOURS destination
            if (dest == NavDestination.HOURS) {
                Column(
                    modifier = Modifier
                        .clip(indicatorShape)
                        .background(
                            color = if (isCalculatorOpen) indicatorColor else Color.Transparent,
                            shape = indicatorShape
                        )
                        .clickable { onCalculatorClick() }
                        .padding(horizontal = hPad, vertical = vPad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        if (isCalculatorOpen) Icons.Filled.Calculate else Icons.Outlined.Calculate,
                        contentDescription = "Calculator",
                        tint = if (isCalculatorOpen) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(iconSize)
                    )
                    AnimatedVisibility(
                        visible = showLabels,
                        enter = expandVertically(NavSpringSize) + fadeIn(NavAnimEnter),
                        exit  = shrinkVertically(NavSpringSize) + fadeOut(NavAnimExit)
                    ) {
                        Text(
                            text = "Calc",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isCalculatorOpen) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCalculatorOpen) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val selected    = dest == currentDestination
            val iconContent = @Composable {
                Icon(
                    if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = dest.label,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(iconSize)
                )
            }

            Box {
                Column(
                    modifier = Modifier
                        .clip(indicatorShape)
                        .background(
                            color = if (selected) indicatorColor else Color.Transparent,
                            shape = indicatorShape
                        )
                        .clickable { onNavigate(dest) }
                        .padding(horizontal = hPad, vertical = vPad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (dest == NavDestination.SUPPLY && supplyNotificationCount > 0) {
                        BadgedBox(badge = { Badge { Text(supplyNotificationCount.toString()) } }) {
                            iconContent()
                        }
                    } else {
                        iconContent()
                    }
                    AnimatedVisibility(
                        visible = showLabels,
                        enter = expandVertically(NavSpringSize) + fadeIn(NavAnimEnter),
                        exit  = shrinkVertically(NavSpringSize) + fadeOut(NavAnimExit)
                    ) {
                        Text(
                            text = dest.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

@Composable
private fun MorphingNavBar(
    currentDestination: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    onCalculatorClick: () -> Unit,
    isCalculatorOpen: Boolean,
    minimized: Boolean,
    destinations: List<NavDestination>,
    supplyNotificationCount: Int,
    hazeState: HazeState? = null,
    searchDecoration: NavBarSearchDecoration? = null,
    cncDecoration: NavBarCncDecoration? = null,
    specialtyDecoration: NavBarSpecialtyDecoration? = null,
    penDecoration: NavBarPenDecoration? = null,
    extendedControls: (@Composable RowScope.() -> Unit)? = null,
    onTimeclockBgEdit: () -> Unit = {}
) {
    // Cache last non-null values so exit animations can still render content
    // when the parent clears decorations before the slide completes.
    var lastSearch    by remember { mutableStateOf(searchDecoration) }
    var lastCnc       by remember { mutableStateOf(cncDecoration) }
    var lastSpecialty by remember { mutableStateOf(specialtyDecoration) }
    var lastPen       by remember { mutableStateOf(penDecoration) }
    if (searchDecoration != null)    lastSearch    = searchDecoration
    if (cncDecoration != null)       lastCnc       = cncDecoration
    if (specialtyDecoration != null) lastSpecialty = specialtyDecoration
    if (penDecoration != null)       lastPen       = penDecoration

    val showExtended    = extendedControls != null
    val hasDecoration   = searchDecoration != null || cncDecoration != null ||
                          specialtyDecoration != null || penDecoration != null
    // Decorations belong to the minimized, non-extended bar only.
    val showDecorations = minimized && !showExtended && hasDecoration
    // Labels only in the roomy full bar (not minimized, not showing extended controls).
    val showLabels      = !minimized && !showExtended
    val rounded         = showExtended || showDecorations

    // Icons morph: 22 (full+labels) → 20 (minimized pill) → 18 (decoration/extended).
    val minIconSize by animateDpAsState(
        targetValue   = when {
            showExtended    -> 18.dp
            showDecorations -> 18.dp
            minimized       -> 20.dp
            else            -> 22.dp
        },
        animationSpec = NavSpringDp,
        label         = "navIconSize"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start  = KKCSpacing.floatingNavMinSideMargin,
                end    = KKCSpacing.floatingNavMinSideMargin,
                bottom = KKCSpacing.floatingNavBottomGap
            )
    ) {
        val cornerRadius by animateDpAsState(
            targetValue   = if (rounded) 26.dp else 999.dp,
            animationSpec = NavSpringDp,
            label         = "navCorner"
        )
        val minNavShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
        Surface(
            modifier       = Modifier.fillMaxWidth(),
            shape          = minNavShape,
            color          = if (hazeState != null) Color.Transparent else MaterialTheme.colorScheme.surface,
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    // ── Extended controls (Hardwoods cut list, etc.) — full-bar path
                    AnimatedVisibility(
                        visible = showExtended,
                        enter   = expandVertically(NavSpringSize) + fadeIn(NavAnimEnter),
                        exit    = shrinkVertically(NavSpringSize) + fadeOut(NavAnimExit)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = extendedControls ?: {}
                        )
                    }

                    // ── Decoration tabs — slide horizontally between types ─────
                    val activeDecor = when {
                        !showDecorations            -> "none"
                        penDecoration != null       -> "pen"
                        searchDecoration != null    -> "search"
                        cncDecoration != null       -> "cnc"
                        specialtyDecoration != null -> "specialty"
                        else                        -> "none"
                    }

                    val decorOrder = listOf("none", "search", "cnc", "specialty", "pen")
                    AnimatedContent(
                        targetState = activeDecor,
                        transitionSpec = {
                            val forward = decorOrder.indexOf(targetState) > decorOrder.indexOf(initialState)
                            val inOffset: (Int) -> Int  = { if (forward) it else -it }
                            val outOffset: (Int) -> Int = { if (forward) -it else it }
                            // Horizontal slide for the tab swap; height grows/shrinks on
                            // the shared spring so the bar settles organically as content
                            // of different heights (search vs pen vs cnc) slides through.
                            ContentTransform(
                                targetContentEnter = slideInHorizontally(tween(NAV_SLIDE_MS, easing = NavEasing), inOffset),
                                initialContentExit = slideOutHorizontally(tween(NAV_SLIDE_MS, easing = NavEasing), outOffset),
                                sizeTransform      = SizeTransform(clip = true) { _, _ -> NavSpringSize }
                            )
                        },
                        label = "decorationTabs"
                    ) { decor ->
                        if (decor != "none") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 0.dp)
                            ) {
                                when (decor) {
                                    "search" -> lastSearch?.let { dec ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            BasicTextField(
                                                value = dec.searchTextValue,
                                                onValueChange = dec.onSearchTextChange,
                                                modifier = Modifier.weight(1f),
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                    color = MaterialTheme.colorScheme.onSurface
                                                ),
                                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                                keyboardActions = KeyboardActions(onGo = { dec.onGo() }),
                                                decorationBox = { innerTextField ->
                                                    Box(contentAlignment = Alignment.CenterStart) {
                                                        if (dec.searchTextValue.text.isEmpty()) {
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
                                                onClick = { dec.onGo() },
                                                shape = MaterialTheme.shapes.extraLarge,
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                            ) {
                                                Text("Go", style = MaterialTheme.typography.labelMedium)
                                            }
                                            Button(
                                                onClick = { dec.onParts() },
                                                enabled = dec.isPartsEnabled,
                                                shape = MaterialTheme.shapes.extraLarge,
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                            ) {
                                                Text("Parts", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                        if (dec.contextLine.isNotBlank()) {
                                            Text(
                                                text = dec.contextLine,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 3.dp, start = 2.dp)
                                            )
                                        }
                                    }
                                    "cnc" -> lastCnc?.let { dec ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            IconButton(
                                                onClick  = dec.onPrevPage,
                                                enabled  = dec.currentPage > 1,
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
                                                onClick  = dec.onNextPage,
                                                enabled  = dec.currentPage < dec.totalPages,
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.ArrowForward,
                                                    contentDescription = "Next sheet",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick  = dec.onOpenSearch,
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
                                                    contentColor   = if (isSkipped) Color.White
                                                                     else MaterialTheme.colorScheme.onSurface
                                                ),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = MaterialTheme.shapes.extraLarge
                                            ) {
                                                Icon(
                                                    Icons.Default.Flag,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(Modifier.width(3.dp))
                                                Text(
                                                    if (isSkipped) "Unskip" else "Skip",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
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
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(Modifier.width(3.dp))
                                                Text(
                                                    if (isComplete) "Done" else "Complete",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
                                        }
                                    }
                                    "specialty" -> lastSpecialty?.let { dec ->
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
                                                Icon(
                                                    Icons.Default.Add,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text("Add Item", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                    "pen" -> lastPen?.let { dec ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            content = dec.content
                                        )
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }

                    // ── Divider — visible when extended controls or a decoration shows
                    AnimatedVisibility(
                        visible = showExtended || showDecorations,
                        enter   = fadeIn(NavAnimEnter),
                        exit    = fadeOut(NavAnimExit)
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }

                    // ── Icon row — always present; labels + size morph in place ─
                    // Stays static while decoration tabs slide above it.
                    MorphingNavIconRow(
                        destinations            = destinations,
                        currentDestination      = currentDestination,
                        isCalculatorOpen        = isCalculatorOpen,
                        supplyNotificationCount = supplyNotificationCount,
                        onNavigate              = onNavigate,
                        onCalculatorClick       = onCalculatorClick,
                        onTimeclockBgEdit       = onTimeclockBgEdit,
                        iconSize                = minIconSize,
                        showLabels              = showLabels
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
