package com.kkc.sheettracker.ui.supply

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.key
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.derivedStateOf
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import com.kkc.sheettracker.ui.theme.kkcZebraTint
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import com.kkc.sheettracker.ui.components.KKCPillContainer
import com.kkc.sheettracker.ui.components.KKCSlidingTabRow
import com.kkc.sheettracker.ui.components.KKCTabItem
import com.kkc.sheettracker.ui.components.contrastOn
import com.kkc.sheettracker.ui.components.rememberKKCPillStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import com.kkc.sheettracker.data.SupplyChange
import com.kkc.sheettracker.data.SupplyNotificationItem
import com.kkc.sheettracker.data.SupplyRepository
import com.kkc.sheettracker.data.SupplySubscriptionData
import com.kkc.sheettracker.data.SupplySubscriptionManager
import com.kkc.sheettracker.data.models.ALL_SUPPLY_STATUSES
import com.kkc.sheettracker.data.models.SUPPLY_STATUS_PRIORITY
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.ToOrderRepository
import com.kkc.sheettracker.data.ToOrderGroup
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.data.SpecialtyProgressStore
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import androidx.compose.material3.Surface
import java.io.File
import com.kkc.sheettracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.ExperimentalMaterial3Api
import com.kkc.sheettracker.ui.dashboard.DashboardShell
import com.kkc.sheettracker.ui.dashboard.DashboardAccent
import com.kkc.sheettracker.ui.dashboard.DashboardSectionHeader
import com.kkc.sheettracker.ui.dashboard.DashboardAccentPill
import com.kkc.sheettracker.ui.dashboard.DashboardSurfaceCard
import com.kkc.sheettracker.ui.dashboard.DashboardSurfaceDefaults
import com.kkc.sheettracker.ui.dashboard.DashboardWidgetRenderer
import com.kkc.sheettracker.ui.dashboard.DashboardInventoryItemModel
import com.kkc.sheettracker.ui.dashboard.DashboardWidgetModel
import com.kkc.sheettracker.ui.dashboard.buildSupplyCategoryWidgets
import com.kkc.sheettracker.ui.dashboard.getSoftStatusColors

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.data.SupplyBarcodeStore
import com.kkc.sheettracker.data.ScanMode
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.input.TextFieldValue
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SupplyDashboardScreen(
    basePath: String,
    tabletId: String,
    employeeName: String,
    subscriptionManager: SupplySubscriptionManager,
    active: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember(basePath) { SupplyRepository(basePath) }
    val scope = rememberCoroutineScope()

    val preferencesStore = remember(context) { UiPreferencesStore(context) }
    // Categories are cached on the tablet (they rarely change): the full tab bar and column
    // headers show instantly from the cache, then items load in behind them.
    var categories by remember { mutableStateOf(preferencesStore.getSupplyCategoriesCache()) }
    var items by remember { mutableStateOf<List<SupplyItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var statusSheetItem by remember { mutableStateOf<SupplyItem?>(null) }
    var activeModal by remember { mutableStateOf<SupplyDashboardModal?>(null) }
    var editChromeState by remember { mutableStateOf<SupplyItemEditorChromeState?>(null) }
    val barcodeStore = remember(basePath) { SupplyBarcodeStore(basePath, repository) }
    val scanMode by barcodeStore.scanMode.collectAsState()
    val pickPendingBarcode by barcodeStore.pickPendingBarcode.collectAsState()
    var knownBarcodeResult by remember { mutableStateOf<Pair<SupplyItem, String>?>(null) }
    var unknownBarcodeResult by remember { mutableStateOf<String?>(null) }

    // ── Scaffold nav bar search decoration ─────────────────────────────────────
    // Publishing a fresh NavBarSearchDecoration every recomposition (data class,
    // fresh lambda instances) makes every reader of navBarDeco.searchDecoration see
    // a "changed" value each time, invalidating them and feeding another publication
    // -- the same self-sustaining recompose loop found and fixed in UnifiedJobsScreen
    // (see JobsSearchNavBar.kt). Keep the decoration's identity stable across
    // recompositions where the visible query text hasn't changed by remembering it
    // on `searchQuery` and routing the callbacks through rememberUpdatedState so
    // they still call the latest closures without forcing a new decoration.
    val navBarDeco = LocalNavBarDecoration.current
    val currentSearchQuery = searchQuery
    val supplySearchDecoration = remember(currentSearchQuery) {
        NavBarSearchDecoration(
            searchTextValue    = currentSearchQuery,
            onSearchTextChange = { searchQuery = it },
            onGo               = {},          // free-text filter: no explicit submit needed
            isPartsEnabled     = false,
            onParts            = {},
            contextLine        = if (currentSearchQuery.text.isNotBlank())
                                   "Filtering buckets by \"${currentSearchQuery.text}\"" else "",
            placeholder        = "Search Inventory...",
            showParts          = false,
            onScan             = { barcodeStore.setScanMode(ScanMode.Global) }
        )
    }
    SideEffect {
        if (active) {
            navBarDeco.owner = "supply"
            navBarDeco.searchDecoration = supplySearchDecoration
        }
    }
    // After transition settles, reset keepSearchDeco to allow icons to shrink to 18dp
    // (mirrors Supply→Jobs where icons grow during slide)
    LaunchedEffect(active) {
        if (active) {
            kotlinx.coroutines.delay(350)
            navBarDeco.keepSearchDeco = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (navBarDeco.owner == "supply") {
                if (!navBarDeco.keepSearchDeco) {
                    navBarDeco.searchDecoration = null
                }
                navBarDeco.owner = ""
            }
        }
    }
    var itemToConfirmLink by remember { mutableStateOf<Pair<SupplyItem, String>?>(null) }
    var pendingNewItemBarcode by remember { mutableStateOf<String?>(null) }
    // Monotonic guard for the status-change write-then-reload sequence below: each status pick
    // launches its own write+reload coroutine, and two picks in quick succession can have the
    // older reload's getItems() result land after the newer one, stomping fresher state. Only
    // the reload that's still the most-recently-issued one when it completes is applied.
    val itemsReloadRequestId = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    val subscriptionData by subscriptionManager.subscriptionData.collectAsState()
    val notificationCount by subscriptionManager.notificationCount.collectAsState()
    var notifications by remember { mutableStateOf<List<SupplyNotificationItem>>(emptyList()) }
    val categoryMap = remember(categories) { categories.associateBy { it.id } }

    // "To Order" tab: cross-job aggregation of specialty/checklist TO_ORDER items, mirroring the
    // Hours Tracker web "To Order" tab. Placed next to the Needs Attention tab. Visible to every
    // user; only admin mode can check items off or edit them (see ToOrderPage `editable`).
    val isAdminMode by AdminModeController.enabled.collectAsState()
    // Read synchronously (a small prefs value) so the custom order is right on the first frame.
    var savedTabOrder by remember { mutableStateOf(preferencesStore.getSupplyTabOrder()) }
    LaunchedEffect(context) {
        savedTabOrder = preferencesStore.getSupplyTabOrder()
    }
    val supplyTabs = remember(categories, savedTabOrder) {
        // Ensure utility tabs are always at the beginning of the savedTabOrder
        val utilityIds = listOf("updates", "needs_attention", "to_order")
        val cleanOrder = utilityIds + savedTabOrder.filter { it !in utilityIds }
        buildSupplyTabsList(categories, cleanOrder)
    }
    val boardPageIndex = 3
    val pagerState = rememberPagerState(pageCount = { boardPageIndex + 1 })
    val board = rememberSupplyBoardState()
    // Safety net: if a gesture ever leaves the pager at rest between two pages (nested board
    // scrolling can end without the pager getting a fling), glide it to the nearest page.
    LaunchedEffect(pagerState) {
        // A drag that starts on the board moves the pager without marking the pager as
        // scrolling -- only the board is -- so both must be idle, or this fights the finger.
        fun stranded() = !pagerState.isScrollInProgress && !board.isScrollInProgress &&
            abs(pagerState.currentPageOffsetFraction) > 0.001f
        snapshotFlow { stranded() }.collectLatest { isStranded ->
            if (!isStranded) return@collectLatest
            // Give the release fling a moment to start before stepping in.
            delay(150)
            if (stranded()) pagerState.animateScrollToPage(pagerState.currentPage)
        }
    }

    val sortedCategories = remember(categories, savedTabOrder) {
        val catMap = categories.associateBy { it.id }
        val sorted = mutableListOf<SupplyCategory>()
        savedTabOrder.forEach { id ->
            catMap[id]?.let { sorted.add(it) }
        }
        categories.forEach { cat ->
            if (!sorted.contains(cat)) sorted.add(cat)
        }
        sorted
    }

    val selectedTabIndex by remember(supplyTabs, pagerState.currentPage, board) {
        derivedStateOf {
            val idx = if (pagerState.currentPage < boardPageIndex) {
                pagerState.currentPage
            } else {
                // The column that is mostly at the left edge: once it is scrolled more than
                // halfway out, the next one counts as selected.
                val activeKey = board.activeKey()
                val activeTab = activeKey?.let { key -> supplyTabs.indexOfFirst { it.id == key } } ?: -1
                if (activeTab >= 0) activeTab.coerceAtLeast(boardPageIndex) else boardPageIndex
            }
            idx.coerceIn(0, supplyTabs.lastIndex.coerceAtLeast(0))
        }
    }

    val specialtyStore = remember(basePath, tabletId) {
        SpecialtyProgressStore(File(basePath), tabletId, readOnly = false)
    }
    val toOrderRepo = remember(basePath, tabletId) {
        ToOrderRepository(
            engine = UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), BuildConfig.DEBUG),
            specialtyStore = specialtyStore
        )
    }
    var toOrderGroups by remember { mutableStateOf<List<ToOrderGroup>>(emptyList()) }
    // Starts true: the scan is deferred (below), and the page should show a spinner, not
    // "Nothing to order", until it has run.
    var toOrderLoading by remember { mutableStateOf(true) }
    var editingToOrderItem by remember { mutableStateOf<Pair<String, SpecialtyResolvedItem>?>(null) }
    // Cross-job scan (~2s on a tablet). Deferred until the first item load finishes so it doesn't
    // compete with the board/items I/O that the user is actually waiting on.
    var initialItemsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(initialItemsLoaded) {
        if (!initialItemsLoaded) return@LaunchedEffect
        toOrderLoading = true
        val t0 = android.os.SystemClock.elapsedRealtime()
        toOrderGroups = withContext(Dispatchers.IO) { toOrderRepo.loadGroups() }
        android.util.Log.d("SupplyPerf", "toOrder.loadGroups ${android.os.SystemClock.elapsedRealtime() - t0}ms groups=${toOrderGroups.size}")
        toOrderLoading = false
    }

    // Categories can reload smaller/empty while the pager still points at a former category page.
    // Snap back into range so the tab row never reads an index past the tab list.
    LaunchedEffect(boardPageIndex) {
        if (pagerState.currentPage > boardPageIndex) {
            pagerState.scrollToPage(boardPageIndex)
        }
    }

    suspend fun loadData(showLoading: Boolean = true) {
        if (showLoading) {
            isLoading = true
        }
        errorMessage = null
        try {
            // Categories first (and cached) so the tab bar is settled before the slower item load.
            val t0 = android.os.SystemClock.elapsedRealtime()
            val cats = withContext(Dispatchers.IO) { repository.getCategories() }.sortedBy { it.position }
            if (cats != categories) categories = cats
            withContext(Dispatchers.IO) { preferencesStore.setSupplyCategoriesCache(cats) }
            val t1 = android.os.SystemClock.elapsedRealtime()
            val its = withContext(Dispatchers.IO) { repository.getItems() }
            android.util.Log.d("SupplyPerf", "categories ${t1 - t0}ms, getItems ${android.os.SystemClock.elapsedRealtime() - t1}ms items=${its.size}")
            items = its
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load supply data"
        } finally {
            if (showLoading) {
                isLoading = false
            }
            initialItemsLoaded = true
        }
    }

    suspend fun reloadUpdates() {
        val t0 = android.os.SystemClock.elapsedRealtime()
        notifications = withContext(Dispatchers.IO) { subscriptionManager.scanForUpdates() }
        android.util.Log.d("SupplyPerf", "scanForUpdates ${android.os.SystemClock.elapsedRealtime() - t0}ms")
    }

    fun openDetailModal(itemId: String) {
        val pending = pickPendingBarcode
        if (pending != null) {
            val item = items.firstOrNull { it.id == itemId }
            if (item != null) {
                itemToConfirmLink = Pair(item, pending)
            }
        } else {
            editChromeState = null
            activeModal = SupplyDashboardModal.Detail(itemId)
        }
    }

    fun openNewItemModal(categoryId: String) {
        editChromeState = null
        activeModal = SupplyDashboardModal.NewItem(categoryId)
    }

    fun dismissSupplyModal() {
        activeModal = null
        editChromeState = null
        scope.launch {
            loadData(showLoading = false)
            reloadUpdates()
        }
    }

    val lowEndMode = LocalLowEndMode.current
    var hasObservedSupplyActivation by remember(basePath) { mutableStateOf(false) }
    var wasSupplyActive by remember(basePath) { mutableStateOf(false) }
    LaunchedEffect(basePath, active) {
        val shouldLoad = !hasObservedSupplyActivation ||
            shouldReloadSupplyOnActivation(wasSupplyActive, active)
        hasObservedSupplyActivation = true
        wasSupplyActive = active
        if (!active || !shouldLoad) return@LaunchedEffect
        if (lowEndMode.lazyLoadingActive) delay(500)
        loadData()
    }
    DisposableEffect(lifecycleOwner, active) {
        // addObserver() on an already-resumed lifecycle replays ON_RESUME synchronously, which
        // re-ran loadData() every time Supply became active — on top of the activation load
        // above (two full item loads in parallel). Only reload on a real resume after a pause.
        var pausedSinceRegister = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) pausedSinceRegister = true
            if (event == Lifecycle.Event.ON_RESUME && active && pausedSinceRegister) {
                scope.launch { loadData(showLoading = false) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(items, subscriptionData) {
        // Skip the run keyed on the initial empty list; the loaded items trigger it again anyway.
        if (isLoading && items.isEmpty()) return@LaunchedEffect
        reloadUpdates()
    }

    val boardSearchMatches = remember(items, searchQuery) {
        if (searchQuery.text.isBlank()) items else items.filter {
            it.name.contains(searchQuery.text, ignoreCase = true) ||
                it.fields["sku"]?.contains(searchQuery.text, ignoreCase = true) == true
        }
    }
    val boardItemsByCategory = remember(boardSearchMatches) { boardSearchMatches.groupBy { it.categoryId } }
    val boardCategories = remember(sortedCategories, boardItemsByCategory, searchQuery) {
        if (searchQuery.text.isBlank()) sortedCategories
        else sortedCategories.filter { !boardItemsByCategory[it.id].isNullOrEmpty() }
    }

    val currentCategoryId = if (!isLoading && searchQuery.text.isBlank() && supplyTabs.isNotEmpty()) {
        val activeTab = supplyTabs.getOrNull(pagerState.currentPage)
        if (activeTab?.type is SupplyTabType.CategoryTab) {
            activeTab.type.category.id
        } else null
    } else null

    DashboardShell(
        title = "Supply Inventory",
        subtitle = "Supply",
        loading = isLoading,
        showLoadingBar = false,
        errorMessage = errorMessage,
        emptyMessage = "No supply data is available yet.",
        hasContent = !isLoading && errorMessage == null && (items.isNotEmpty() || categories.isNotEmpty() || notifications.isNotEmpty()),
        scrollable = false,
        onRefresh = { scope.launch { loadData(); reloadUpdates() } },
        topBarActions = {
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Add Category") },
                        onClick = {
                            showOverflowMenu = false
                            newCategoryName = ""
                            showAddCategoryDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Reorder Tabs") },
                        onClick = {
                            showOverflowMenu = false
                            activeModal = SupplyDashboardModal.ReorderTabs
                        }
                    )
                }
            }
        },
    ) {
        // Pick mode banner
        if (lowEndMode.animationsDisabled) {
            if (pickPendingBarcode != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Tap an item to link barcode", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        IconButton(onClick = { barcodeStore.clearPickMode() }) {
                            Icon(Icons.Filled.Close, "Cancel link", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        } else {
            AnimatedVisibility(visible = pickPendingBarcode != null, enter = expandVertically(), exit = shrinkVertically()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Tap an item to link barcode", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        IconButton(onClick = { barcodeStore.clearPickMode() }) {
                            Icon(Icons.Filled.Close, "Cancel link", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }

        run {
            // Same sliding selector as the rest of the app; badges and the subscribed bell ride
            // inside the tab labels.
            val supplyPillStyle = rememberKKCPillStyle()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                KKCPillContainer(style = supplyPillStyle, modifier = Modifier.fillMaxWidth()) {
                    KKCSlidingTabRow(
                        modifier = Modifier.fillMaxWidth(),
                        // While the pager or board is being swiped, the pill follows the exact
                        // scroll position; it settles onto the selected tab once scrolling stops.
                        trackingPosition = {
                            if (!pagerState.isScrollInProgress && !board.isScrollInProgress) {
                                null
                            } else {
                                // Fractional tab index of the board's leftmost column.
                                val boardPos = run {
                                    val p = board.position() ?: return@run boardPageIndex.toFloat()
                                    val lower = p.toInt()
                                    fun tabOf(i: Int) = board.keys.getOrNull(i)
                                        ?.let { key -> supplyTabs.indexOfFirst { it.id == key } }
                                        ?.takeIf { it >= 0 }
                                    val firstTab = tabOf(lower) ?: return@run boardPageIndex.toFloat()
                                    val nextTab = tabOf(lower + 1) ?: firstTab
                                    firstTab + (nextTab - firstTab) * (p - lower)
                                }
                                val pagerPos = pagerState.currentPage + pagerState.currentPageOffsetFraction
                                val lastUtility = (boardPageIndex - 1).toFloat()
                                when {
                                    pagerPos <= lastUtility -> pagerPos
                                    pagerPos >= boardPageIndex -> boardPos
                                    else -> lastUtility + (boardPos - lastUtility) * (pagerPos - lastUtility)
                                }
                            }
                        },
                        items = supplyTabs.mapIndexed { index, tabItem ->
                            KKCTabItem(
                                label = tabItem.name.uppercase(LocalLocale.current.platformLocale),
                                isSelected = selectedTabIndex == index,
                                alwaysBold = true,
                                reserveBadge = tabItem.type == SupplyTabType.NeedsAttention ||
                                    tabItem.type == SupplyTabType.ToOrder,
                                badgeCount = when (tabItem.type) {
                                    SupplyTabType.Updates -> notificationCount
                                    SupplyTabType.NeedsAttention ->
                                        items.count { (SUPPLY_STATUS_PRIORITY[it.status] ?: 99) < 5 }
                                    SupplyTabType.ToOrder -> toOrderGroups.sumOf { it.items.size }
                                    is SupplyTabType.CategoryTab -> 0
                                },
                                showBell = (tabItem.type as? SupplyTabType.CategoryTab)?.let { type ->
                                    subscriptionData.subscribedCategoryIds.contains(type.category.id)
                                } == true,
                                onClick = {
                                    scope.launch {
                                        if (tabItem.type is SupplyTabType.CategoryTab) {
                                            if (lowEndMode.animationsDisabled) {
                                                pagerState.scrollToPage(boardPageIndex)
                                            } else {
                                                pagerState.animateScrollToPage(boardPageIndex)
                                            }
                                            board.scrollToColumn(
                                                tabItem.type.category.id,
                                                animate = !lowEndMode.animationsDisabled
                                            )
                                        } else {
                                            if (lowEndMode.animationsDisabled) {
                                                pagerState.scrollToPage(index)
                                            } else {
                                                pagerState.animateScrollToPage(index)
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    )
                }
            }

                HorizontalPager(
                    state = pagerState,
                    // Keep every page (Updates, Needs Attention, To Order, board) composed while
                    // on Supply so switching never rebuilds one.
                    beyondViewportPageCount = boardPageIndex,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    if (page == boardPageIndex) {
                        val isDark = LocalKKCIsDarkTheme.current
                        val palette = if (isDark) HEADER_COLORS_DARK else HEADER_COLORS_LIGHT
                        // Greedy coloring: pick a color that differs from the previous neighbor
                        val headerColors = remember(sortedCategories, isDark) {
                            val result = mutableMapOf<String, Color>()
                            var prev: Color? = null
                            sortedCategories.forEach { cat ->
                                var pick = Math.abs(cat.id.hashCode()) % palette.size
                                if (prev != null && palette[pick] == prev) {
                                    pick = (pick + 1) % palette.size
                                }
                                val color = palette[pick]
                                result[cat.id] = color
                                prev = color
                            }
                            result
                        }
                        LaunchedEffect(boardCategories) { board.updateKeys(boardCategories.map { it.id }) }
                        // Columns are built in the background: the first few right away, then one
                        // per frame, so opening Supply doesn't stall on every card at once but all
                        // columns exist (and have real widths) before the user pans to them.
                        var builtColumns by remember { mutableIntStateOf(4) }
                        LaunchedEffect(boardCategories.size) {
                            while (builtColumns < boardCategories.size) {
                                withFrameNanos { }
                                builtColumns++
                            }
                        }
                        val boardEdgeConnection = rememberSupplyBoardEdgeConnection(board, pagerState)
                        val boardDensity = LocalDensity.current
                        // Plain (non-lazy) row: every column stays composed, so panning never
                        // composes a column mid-scroll.
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 12.dp, bottom = 120.dp)
                                .onSizeChanged { board.viewportPx = it.width }
                                .nestedScroll(boardEdgeConnection)
                                .horizontalScroll(board.scroll),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 4dp + 12dp spacing = 16dp edge inset; kept as spacers so column
                            // positions are plain row coordinates.
                            Spacer(Modifier.width(4.dp))
                            boardCategories.take(builtColumns).forEach { category ->
                                key(category.id) {
                                    val categoryItems = boardItemsByCategory[category.id].orEmpty()
                                    CategoryBoardColumn(
                                        category = category,
                                        items = categoryItems,
                                        headerColor = headerColors[category.id] ?: palette[0],
                                        subscriptionManager = subscriptionManager,
                                        subscriptionData = subscriptionData,
                                        onAddItem = { openNewItemModal(category.id) },
                                        onOpenItem = ::openDetailModal,
                                        onLongPress = { item -> statusSheetItem = item },
                                        modifier = Modifier.onPlaced { coordinates ->
                                            board.columns[category.id] =
                                                coordinates.positionInParent().x.roundToInt() to coordinates.size.width
                                        }
                                    )
                                }
                            }
                            // Room to scroll past the last column (60% of the board's width) so
                            // the last categories can reach the left edge and be selected.
                            Spacer(Modifier.width(with(boardDensity) { (board.viewportPx * 0.6f).toDp() }))
                        }
                    } else {
                        val tabItem = supplyTabs.getOrNull(page)
                        if (tabItem != null) {
                            when (val type = tabItem.type) {
                                SupplyTabType.Updates -> UpdatesPage(
                                    notifications = notifications,
                                    categories = categories,
                                    subscriptionManager = subscriptionManager,
                                    onOpenItem = ::openDetailModal,
                                    reloadUpdates = { scope.launch { reloadUpdates() } },
                                    modifier = Modifier.fillMaxSize()
                                )
                                SupplyTabType.NeedsAttention -> NeedsAttentionPage(
                                    items = items,
                                    categories = categories,
                                    onOpenItem = ::openDetailModal,
                                    onLongPress = { item -> statusSheetItem = item },
                                    modifier = Modifier.fillMaxSize()
                                )
                                SupplyTabType.ToOrder -> ToOrderPage(
                                    groups = toOrderGroups,
                                    loading = toOrderLoading,
                                    editable = isAdminMode,
                                    onToggleComplete = { jobFolder, resolvedItem, completed ->
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                specialtyStore.setCompletion(
                                                    jobFolderName = jobFolder,
                                                    itemId = resolvedItem.item.id,
                                                    completionKey = SpecialtyProgressStore.ITEM_COMPLETION_KEY,
                                                    completed = completed,
                                                    completedBy = employeeName.ifBlank { "Floor" },
                                                    completedAt = java.time.Instant.now().toString()
                                                )
                                                // Auto-fill orderDate on first check-off
                                                if (completed && resolvedItem.item.orderDate.isNullOrBlank()) {
                                                    val today = defaultToOrderDate(java.time.LocalDate.now())
                                                    val item = resolvedItem.item
                                                    specialtyStore.updateToOrderItem(
                                                        jobFolderName = jobFolder,
                                                        itemId = item.id,
                                                        name = item.name,
                                                        cabinetNumbers = item.cabinetNumbers,
                                                        supplier = item.supplier,
                                                        model = item.model,
                                                        orderDate = today,
                                                        tracking = item.tracking,
                                                        orderUrl = item.orderUrl,
                                                        notes = item.notes,
                                                        quantity = item.quantity,
                                                        material = item.material,
                                                        dimensions = item.dimensions
                                                    )
                                                }
                                            }
                                            toOrderGroups = withContext(Dispatchers.IO) { toOrderRepo.loadGroups() }
                                        }
                                    },
                                    onEditItem = { jobFolder, resolvedItem ->
                                        editingToOrderItem = Pair(jobFolder, resolvedItem)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                                else -> {}
                            }
                        }
                    }
                }
            }
    }
    if (showAddCategoryDialog) {
        SupplyModalFrame(
            title = "New Category",
            onDismiss = { showAddCategoryDialog = false },
            modifier = Modifier.heightIn(max = 420.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text("Category name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showAddCategoryDialog = false }) { Text("Cancel") }
                    Button(
                        onClick = {
                            val name = newCategoryName.trim()
                            if (name.isNotBlank()) {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { repository.createCategory(name) } }
                                    result.onFailure { Toast.makeText(context, "Failed to create category: ${it.message}", Toast.LENGTH_LONG).show() }
                                    showAddCategoryDialog = false
                                    loadData(showLoading = false)
                                }
                            }
                        },
                        enabled = newCategoryName.isNotBlank()
                    ) { Text("Create") }
                }
            }
        }
    }

    when (val modal = activeModal) {
        is SupplyDashboardModal.Detail -> {
            val isSubscribed = subscriptionData.subscribedItemIds.contains(modal.itemId)
            val item = items.firstOrNull { it.id == modal.itemId }
            val itemTitle = item?.name ?: "Supply Item"
            SupplyModalFrame(
                // Category as the header eyebrow so the status-tinted band isn't an empty strip.
                title = categories.firstOrNull { it.id == item?.categoryId }?.name?.uppercase().orEmpty(),
                onDismiss = { dismissSupplyModal() },
                headerTint = supplyStatusHeaderTint(item?.status),
                wrapContentHeight = true,
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            subscriptionManager.toggleItemSubscription(modal.itemId)
                        }
                    }) {
                        Icon(
                            imageVector = if (isSubscribed) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                            contentDescription = if (isSubscribed) "Unsubscribe from notifications" else "Subscribe to notifications",
                            tint = if (isSubscribed) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = {
                        editChromeState = null
                        activeModal = SupplyDashboardModal.EditItem(modal.itemId)
                    }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit item")
                    }
                }
            ) {
                SupplyItemDetailScreen(
                    itemId = modal.itemId,
                    basePath = basePath,
                    tabletId = tabletId,
                    employeeName = employeeName,
                    onBack = { dismissSupplyModal() },
                    onEdit = {
                        editChromeState = null
                        activeModal = SupplyDashboardModal.EditItem(modal.itemId)
                    },
                    subscriptionManager = subscriptionManager,
                    barcodeStore = barcodeStore
                )
            }
        }
        is SupplyDashboardModal.EditItem -> {
            val item = items.firstOrNull { it.id == modal.itemId }
            SupplyModalFrame(
                title = "Edit Item",
                onDismiss = { dismissSupplyModal() },
                headerTint = supplyStatusHeaderTint(editChromeState?.status ?: item?.status),
                actions = {
                    editChromeState?.let { chrome ->
                        TextButton(
                            onClick = chrome.onSave,
                            enabled = chrome.canSave
                        ) {
                            if (chrome.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Save")
                            }
                        }
                    }
                }
            ) {
                SupplyItemEditScreen(
                    itemId = modal.itemId,
                    initialCategoryId = null,
                    basePath = basePath,
                    tabletId = tabletId,
                    employeeName = employeeName,
                    onBack = { dismissSupplyModal() },
                    onSaved = { savedItemId ->
                        scope.launch {
                            loadData(showLoading = false)
                            reloadUpdates()
                            editChromeState = null
                            activeModal = SupplyDashboardModal.Detail(savedItemId)
                        }
                    },
                    onChromeStateChanged = { editChromeState = it }
                )
            }
        }
        is SupplyDashboardModal.NewItem -> {
            SupplyModalFrame(
                title = "New Item",
                onDismiss = { dismissSupplyModal() },
                headerTint = supplyStatusHeaderTint(editChromeState?.status ?: "IN STOCK"),
                actions = {
                    editChromeState?.let { chrome ->
                        TextButton(
                            onClick = chrome.onSave,
                            enabled = chrome.canSave
                        ) {
                            if (chrome.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Save")
                            }
                        }
                    }
                }
            ) {
                SupplyItemEditScreen(
                    itemId = null,
                    initialCategoryId = modal.categoryId,
                    basePath = basePath,
                    tabletId = tabletId,
                    employeeName = employeeName,
                    onBack = { dismissSupplyModal() },
                    onSaved = { savedItemId ->
                        scope.launch {
                            val barcode = pendingNewItemBarcode
                            if (barcode != null) {
                                barcodeStore.link(barcode, savedItemId)
                                pendingNewItemBarcode = null
                            }
                            loadData(showLoading = false)
                            reloadUpdates()
                            editChromeState = null
                            activeModal = SupplyDashboardModal.Detail(savedItemId)
                        }
                    },
                    onChromeStateChanged = { editChromeState = it }
                )
            }
        }
        is SupplyDashboardModal.ReorderTabs -> {
            SupplyModalFrame(
                title = "Reorder Tabs",
                onDismiss = { dismissSupplyModal() },
                headerTint = null
            ) {
                val categoryTabs = remember(supplyTabs) {
                    supplyTabs.filter { it.type is SupplyTabType.CategoryTab }
                }
                SupplyTabReorderScreen(
                    availableTabs = categoryTabs,
                    preferencesStore = preferencesStore,
                    onOrderChanged = { newOrder ->
                        val selectedTabId = supplyTabs.getOrNull(selectedTabIndex)?.id
                        savedTabOrder = newOrder
                        if (selectedTabId != null) {
                            val utilityIds = listOf("updates", "needs_attention", "to_order")
                            val cleanOrder = utilityIds + newOrder.filter { it !in utilityIds }
                            val nextTabs = buildSupplyTabsList(categories, cleanOrder)
                            val newIndex = nextTabs.indexOfFirst { it.id == selectedTabId }
                            if (newIndex >= 0) {
                                scope.launch {
                                    val nextTabItem = nextTabs.getOrNull(newIndex)
                                    if (nextTabItem?.type is SupplyTabType.CategoryTab) {
                                        pagerState.scrollToPage(boardPageIndex)
                                        board.scrollToColumn(nextTabItem.type.category.id, animate = false)
                                    } else {
                                        pagerState.scrollToPage(newIndex)
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
        null -> Unit
    }

    editingToOrderItem?.let { (jobFolder, resolvedItem) ->
        ToOrderEditDialog(
            item = resolvedItem.item,
            onDismiss = { editingToOrderItem = null },
            onSave = { name, cabNums, supplier, model, orderDate, tracking, orderUrl, notes, qty, material, dimensions ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            specialtyStore.updateToOrderItem(
                                jobFolderName = jobFolder,
                                itemId = resolvedItem.item.id,
                                name = name,
                                cabinetNumbers = cabNums,
                                supplier = supplier,
                                model = model,
                                orderDate = orderDate,
                                tracking = tracking,
                                orderUrl = orderUrl,
                                notes = notes,
                                quantity = qty,
                                material = material,
                                dimensions = dimensions
                            )
                        }
                    }
                    result.onFailure {
                        Toast.makeText(context, "Failed to save: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                    editingToOrderItem = null
                    toOrderGroups = withContext(Dispatchers.IO) { toOrderRepo.loadGroups() }
                }
            }
        )
    }

    statusSheetItem?.let { item ->
        SupplyPickerDialog(
            title = "Change Status: ${item.name}",
            options = ALL_SUPPLY_STATUSES.map { status ->
                SupplyPickerOption(
                    id = status,
                    label = status,
                    selected = item.status == status,
                    onClick = {
                        scope.launch {
                            statusSheetItem = null
                            performSupplyStatusChange(
                                setStatus = { withContext(Dispatchers.IO) { repository.setStatus(item.id, status, employeeName.ifBlank { "Floor" }, tabletId) } },
                                reloadItems = { withContext(Dispatchers.IO) { repository.getItems() } },
                                currentItems = { items },
                                requestIdCounter = itemsReloadRequestId,
                                onItemsReloaded = { items = it },
                                onFailure = { Toast.makeText(context, "Failed to change status: ${it.message}", Toast.LENGTH_LONG).show() }
                            )
                        }
                    },
                    icon = {
                        Box(modifier = Modifier.size(12.dp).background(supplyStatusColor(SUPPLY_STATUS_PRIORITY[status] ?: 99), CircleShape))
                    }
                )
            },
            onDismiss = { statusSheetItem = null },
            headerTint = supplyStatusHeaderTint(item.status)
        )
    }

    // Scanner overlay (Global mode)
    if (scanMode != ScanMode.Idle && scanMode == ScanMode.Global) {
        val knownSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val unknownSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        SupplyScannerOverlay(
            barcodeStore = barcodeStore,
            isModalActive = knownBarcodeResult != null || unknownBarcodeResult != null,
            onDismiss = { barcodeStore.setScanMode(ScanMode.Idle) },
            onKnownBarcode = { item, barcode -> knownBarcodeResult = Pair(item, barcode) },
            onUnknownBarcode = { barcode -> unknownBarcodeResult = barcode }
        )

        knownBarcodeResult?.let { (item, barcode) ->
            KnownBarcodeSheet(
                item = item,
                categoryName = categoryMap[item.categoryId]?.name ?: "",
                sheetState = knownSheetState,
                onStatusPick = { newStatus ->
                    scope.launch {
                        withContext(Dispatchers.IO) { repository.setStatus(item.id, newStatus, employeeName, tabletId) }
                        barcodeStore.setScanMode(ScanMode.Idle)
                        knownBarcodeResult = null
                        loadData()
                    }
                },
                onViewItem = {
                    barcodeStore.setScanMode(ScanMode.Idle)
                    knownBarcodeResult = null
                    activeModal = SupplyDashboardModal.Detail(item.id)
                },
                onDismiss = { knownBarcodeResult = null }
            )
        }

        unknownBarcodeResult?.let { barcode ->
            UnknownBarcodeSheet(
                barcode = barcode,
                sheetState = unknownSheetState,
                onLinkToExisting = {
                    unknownBarcodeResult = null
                    barcodeStore.setScanMode(ScanMode.Idle)
                    barcodeStore.setPickPendingBarcode(barcode)
                },
                onAddNewItem = {
                    unknownBarcodeResult = null
                    barcodeStore.setScanMode(ScanMode.Idle)
                    pendingNewItemBarcode = barcode
                    openNewItemModal(currentCategoryId ?: categories.firstOrNull()?.id ?: "")
                },
                onDismiss = { unknownBarcodeResult = null }
            )
        }
    }

    // Pick mode link confirmation dialog
    itemToConfirmLink?.let { (item, barcode) ->
        AlertDialog(
            onDismissRequest = { itemToConfirmLink = null },
            title = { Text("Link barcode?") },
            text = { Text("Link \"${barcode.take(20)}\" to ${item.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    itemToConfirmLink = null
                    scope.launch { barcodeStore.link(barcode, item.id); barcodeStore.clearPickMode(); loadData() }
                }) { Text("Link") }
            },
            dismissButton = { TextButton(onClick = { itemToConfirmLink = null }) { Text("Cancel") } }
        )
    }
}

private sealed interface SupplyDashboardModal {
    data class Detail(val itemId: String) : SupplyDashboardModal
    data class EditItem(val itemId: String) : SupplyDashboardModal
    data class NewItem(val categoryId: String) : SupplyDashboardModal
    object ReorderTabs : SupplyDashboardModal
}

/**
 * Writes a status change then reloads the full item list, applying the reload to state only if
 * no newer status change was issued while this reload was in flight. Without this guard, two
 * status changes issued in quick succession (e.g. two rapid taps in the status picker) can have
 * the older write's reload land after the newer one's, silently reverting the UI to stale data
 * even though the newer write already succeeded on disk.
 *
 * [requestIdCounter] must be shared across all callers whose results should be mutually
 * ordered (i.e. one counter per `items` state). Extracted from the Composable body so the
 * ordering guard is unit-testable without Compose/Android.
 */
internal suspend fun performSupplyStatusChange(
    setStatus: suspend () -> Unit,
    reloadItems: suspend () -> List<SupplyItem>,
    currentItems: () -> List<SupplyItem>,
    requestIdCounter: java.util.concurrent.atomic.AtomicLong,
    onItemsReloaded: (List<SupplyItem>) -> Unit,
    onFailure: (Throwable) -> Unit = {}
) {
    val statusResult = runCatching { setStatus() }
    statusResult.onFailure(onFailure)
    val requestId = requestIdCounter.incrementAndGet()
    val reloaded = runCatching { reloadItems() }.getOrDefault(currentItems())
    if (requestId == requestIdCounter.get()) {
        onItemsReloaded(reloaded)
    }
}

/**
 * Returns true only for the inactive-to-active transition that occurs when the persistent
 * top-level Supply tab is shown again. The dashboard keeps its Compose state while another tab
 * is visible, so this transition is the opportunity to re-read files Syncthing changed in place.
 */
internal fun shouldReloadSupplyOnActivation(wasActive: Boolean, isActive: Boolean): Boolean =
    !wasActive && isActive

@Composable
private fun CategoryAddHeader(
    category: SupplyCategory,
    onAddItem: () -> Unit
) {
    DashboardSurfaceCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(category.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Category inventory",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onAddItem) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Item")
            }
        }
    }
}

@Composable
private fun EmptyCategoryAddCard(
    category: SupplyCategory,
    onAddItem: () -> Unit
) {
    DashboardSurfaceCard(accent = DashboardAccent.INFO) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("No items in ${category.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add the first item so this category is useful on the floor.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onAddItem) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Item")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesPage(
    notifications: List<SupplyNotificationItem>,
    categories: List<SupplyCategory>,
    subscriptionManager: SupplySubscriptionManager,
    onOpenItem: (String) -> Unit,
    reloadUpdates: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            DashboardSurfaceCard(accent = if (notifications.isEmpty()) DashboardAccent.NEUTRAL else DashboardAccent.INFO) {
                DashboardSectionHeader(
                    title = "Updates",
                    subtitle = if (notifications.isEmpty()) {
                        "No active notifications right now."
                    } else {
                        "${notifications.size} update${if (notifications.size == 1) "" else "s"} across subscribed items"
                    }
                )
                if (notifications.isEmpty()) {
                    Text(
                        "Subscribe to items or categories to receive updates here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        items(notifications, key = { it.item.id }) { notification ->
            @Suppress("DEPRECATION")
            val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                    scope.launch {
                        subscriptionManager.dismissNotification(notification.item.id)
                        reloadUpdates()
                    }
                    true
                } else {
                    false
                }
            })
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            ) {
                DashboardSurfaceCard(
                    accent = supplyAccent(notification.item.status),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenItem(notification.item.id) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(2.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            notification.item.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            categories.find { it.id == notification.item.categoryId }?.name ?: "Unknown Category",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        notification.changes.forEach { change ->
                            val text = when (change) {
                                is SupplyChange.NewSubscriptionOrItem -> "New subscription or item added"
                                is SupplyChange.DetailsUpdated -> "Details updated"
                                is SupplyChange.StatusChanged -> "Status changed to ${change.status}"
                                is SupplyChange.NewComments -> "${change.count} new comment(s)"
                                is SupplyChange.NewAttachments -> "${change.count} new attachment(s)"
                            }
                            Text("• $text", style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    subscriptionManager.dismissNotification(notification.item.id)
                                    reloadUpdates()
                                }
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

private fun toInventoryItemModel(
    item: SupplyItem,
    categoryMap: Map<String, SupplyCategory> = emptyMap()
): DashboardInventoryItemModel {
    val quantity = item.fields["quantity"]?.takeIf { it.isNotBlank() }
    val sku = item.fields["sku"]?.takeIf { it.isNotBlank() }
    val supportingText = listOfNotNull(
        quantity?.let { "Qty $it" },
        item.notes?.takeIf { it.isNotBlank() }
    ).joinToString("\n").ifBlank { null }
    return DashboardInventoryItemModel(
        id = item.id,
        title = item.name,
        subtitle = categoryMap[item.categoryId]?.name ?: "",
        supportingText = supportingText,
        badge = item.status,
        accent = supplyAccent(item.status),
        sku = sku,
        quantity = quantity,
        notes = item.notes
    )
}

fun supplyTierAccent(tier: Int): DashboardAccent = when (tier) {
    1, 2 -> DashboardAccent.DANGER     // OUT / ASAP / NEED
    3, 6 -> DashboardAccent.WARNING    // LOW / NOT ORDERED
    4 -> DashboardAccent.INFO          // ORDERED / IN PROCESS
    5, 7 -> DashboardAccent.SUCCESS    // IN STOCK / COMPLETE / ORDERED (To Order)
    else -> DashboardAccent.SUCCESS    // default, matches prior fallback
}

@Composable
fun supplyStatusColor(tier: Int): Color = DashboardSurfaceDefaults.accentColor(supplyTierAccent(tier))

@Composable
fun supplyStatusHeaderTint(status: String?): Color? {
    val normalized = status?.takeIf { it.isNotBlank() } ?: return null
    return supplyStatusColor(SUPPLY_STATUS_PRIORITY[normalized] ?: 99)
}

fun supplyAccent(status: String): DashboardAccent = when (status.uppercase()) {
    "OUT", "ASAP", "MALFUNCTIONING", "NEED" -> DashboardAccent.DANGER
    "LOW" -> DashboardAccent.WARNING
    "ORDERED", "IN PROCESS", "ACKNOWLEDGED" -> DashboardAccent.INFO
    "IN STOCK", "COMPLETE", "RECEIVED" -> DashboardAccent.SUCCESS
    else -> DashboardAccent.NEUTRAL
}

private data class AttentionTier(
    val title: String,
    val statuses: Set<String>,
    val accent: DashboardAccent
)

private val ATTENTION_TIERS = listOf(
    AttentionTier("Critical", setOf("OUT", "ASAP"), DashboardAccent.DANGER),
    AttentionTier("Urgent", setOf("MALFUNCTIONING", "NEED"), DashboardAccent.DANGER),
    AttentionTier("Low Stock", setOf("LOW"), DashboardAccent.WARNING),
    AttentionTier("In Progress", setOf("ORDERED", "IN PROCESS", "ACKNOWLEDGED"), DashboardAccent.INFO),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeedsAttentionPage(
    items: List<SupplyItem>,
    categories: List<SupplyCategory>,
    onOpenItem: (String) -> Unit,
    onLongPress: (SupplyItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val categoryMap = remember(categories) { categories.associateBy { it.id } }

    val tierGroups = remember(items) {
        ATTENTION_TIERS.mapNotNull { tier ->
            val tierItems = items
                .filter { it.status.uppercase() in tier.statuses }
                .sortedWith(compareBy({ categoryMap[it.categoryId]?.name ?: "" }, { it.name }))
            if (tierItems.isEmpty()) null else tier to tierItems
        }
    }

    if (tierGroups.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("All Clear", style = MaterialTheme.typography.titleMedium)
                Text(
                    "No items need attention right now",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // Same layout as the To Order tab: one collapsible section card per tier. "In Progress"
    // (already ordered) starts collapsed, like fully ordered jobs there.
    var collapsedTiers by remember { mutableStateOf(setOf("In Progress")) }

    // Lazy: only tier sections on screen are composed (cards inside an open section stay eager).
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(tierGroups, key = { (tier, _) -> tier.title }) { (tier, tierItems) ->
            AttentionTierSectionCard(
                tier = tier,
                items = tierItems,
                isCollapsed = tier.title in collapsedTiers,
                categoryMap = categoryMap,
                onToggleCollapsed = {
                    collapsedTiers = if (tier.title in collapsedTiers) {
                        collapsedTiers - tier.title
                    } else {
                        collapsedTiers + tier.title
                    }
                },
                onOpenItem = onOpenItem,
                onLongPress = onLongPress
            )
        }
    }
}

@Composable
private fun AttentionTierSectionCard(
    tier: AttentionTier,
    items: List<SupplyItem>,
    isCollapsed: Boolean,
    categoryMap: Map<String, SupplyCategory>,
    onToggleCollapsed: () -> Unit,
    onOpenItem: (String) -> Unit,
    onLongPress: (SupplyItem) -> Unit
) {
    DashboardSurfaceCard(
        accent = tier.accent,
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleCollapsed)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DashboardSectionHeader(
                title = tier.title,
                subtitle = "${items.size} item${if (items.size == 1) "" else "s"}",
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val chipText = tier.title.uppercase()
                val baseColor = supplyStatusColor(SUPPLY_STATUS_PRIORITY[tier.statuses.first()] ?: 99)
                val (chipBgColor, chipTextColor) = getSoftStatusColors(chipText, baseColor)
                StatusChip(
                    text = chipText,
                    backgroundColor = chipBgColor,
                    contentColor = chipTextColor
                )
                Icon(
                    imageVector = if (isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = if (isCollapsed) "Expand ${tier.title}" else "Collapse ${tier.title}"
                )
            }
        }

        val body: @Composable () -> Unit = {
            // Cards in rows of up to [perRow] (~300dp each), last row kept the same card width.
            BoxWithConstraints(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)
            ) {
                val perRow = ((maxWidth + 12.dp) / (300.dp + 12.dp)).toInt().coerceAtLeast(1)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items.chunked(perRow).forEachIndexed { rowIndex, row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEachIndexed { indexInRow, item ->
                                Box(modifier = Modifier.weight(1f)) {
                                    BoardCard(
                                        zebraIndex = rowIndex * perRow + indexInRow,
                                        item = item,
                                        categoryName = categoryMap[item.categoryId]?.name,
                                        onClick = { onOpenItem(item.id) },
                                        onLongClick = { onLongPress(item) }
                                    )
                                }
                            }
                            repeat(perRow - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        if (LocalLowEndMode.current.animationsDisabled) {
            if (!isCollapsed) body()
        } else {
            AnimatedVisibility(
                visible = !isCollapsed,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                body()
            }
        }
    }
}

/**
 * Cross-job "To Order" view: one section per job, listing that job's specialty + checklist items
 * flagged TO_ORDER. Visible to all users; only editable (check off / edit item) when [editable]
 * (admin mode) is true — everyone else gets a read-only view.
 */
@Composable
private fun ToOrderPage(
    groups: List<ToOrderGroup>,
    loading: Boolean,
    editable: Boolean,
    onToggleComplete: (jobFolderName: String, item: SpecialtyResolvedItem, completed: Boolean) -> Unit,
    onEditItem: (jobFolderName: String, item: SpecialtyResolvedItem) -> Unit,
    modifier: Modifier = Modifier
) {
    // Start in the final state on the very first frame (finished jobs collapsed, jobs with items
    // still to order open) so nothing animates when you swipe onto this tab. Keyed on `loading`
    // so the state is rebuilt from the loaded groups when the first load completes.
    var collapsedJobIds by remember(loading) { mutableStateOf(autoCollapsedToOrderJobIds(groups)) }

    LaunchedEffect(groups) {
        val visibleJobIds = groups.map { it.folderName }.toSet()
        collapsedJobIds = collapsedJobIds
            .filter { it in visibleJobIds }
            .toSet() + autoCollapsedToOrderJobIds(groups)
    }

    if (loading) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (groups.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Nothing to order", style = MaterialTheme.typography.titleMedium)
                Text(
                    "No items are flagged To Order across active jobs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // Lazy: only job sections on screen are composed (items inside an open section stay eager).
    val sections = remember(groups, collapsedJobIds) { buildToOrderJobSections(groups, collapsedJobIds) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(sections, key = { it.group.folderName }) { section ->
            ToOrderJobSectionCard(
                section = section,
                editable = editable,
                onToggleCollapsed = {
                    collapsedJobIds = toggleToOrderJobCollapse(collapsedJobIds, section.group.folderName)
                },
                onToggleComplete = onToggleComplete,
                onEditItem = onEditItem
            )
        }
    }
}

internal data class ToOrderJobSection(
    val group: ToOrderGroup,
    val completedCount: Int,
    val totalCount: Int,
    val isCollapsed: Boolean
) {
    val isFullyOrdered: Boolean = totalCount > 0 && completedCount == totalCount
}

internal fun autoCollapsedToOrderJobIds(groups: List<ToOrderGroup>): Set<String> {
    return groups
        .filter { group -> group.items.isNotEmpty() && group.items.all { it.isComplete } }
        .map { it.folderName }
        .toSet()
}

internal fun toggleToOrderJobCollapse(collapsedJobIds: Set<String>, folderName: String): Set<String> {
    return if (folderName in collapsedJobIds) {
        collapsedJobIds - folderName
    } else {
        collapsedJobIds + folderName
    }
}

internal fun buildToOrderJobSections(
    groups: List<ToOrderGroup>,
    collapsedJobIds: Set<String>
): List<ToOrderJobSection> {
    return groups.map { group ->
        ToOrderJobSection(
            group = group,
            completedCount = group.items.count { it.isComplete },
            totalCount = group.items.size,
            isCollapsed = group.folderName in collapsedJobIds
        )
    }
}

@Composable
private fun ToOrderJobSectionCard(
    section: ToOrderJobSection,
    editable: Boolean,
    onToggleCollapsed: () -> Unit,
    onToggleComplete: (jobFolderName: String, item: SpecialtyResolvedItem, completed: Boolean) -> Unit,
    onEditItem: (jobFolderName: String, item: SpecialtyResolvedItem) -> Unit
) {
    val accent = if (section.isFullyOrdered) DashboardAccent.SUCCESS else DashboardAccent.WARNING

    DashboardSurfaceCard(
        accent = accent,
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleCollapsed)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DashboardSectionHeader(
                    title = section.group.jobName.ifBlank { section.group.folderName },
                    subtitle = buildString {
                        append(section.group.jobNumber.ifBlank { section.group.folderName })
                        append(" • ")
                        append(section.completedCount)
                        append(" / ")
                        append(section.totalCount)
                        append(" ordered")
                    }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val statusText = if (section.isFullyOrdered) "ORDERED" else "OPEN"
                val baseColor = supplyStatusColor(if (section.isFullyOrdered) 7 else 6)
                val (chipBgColor, chipTextColor) = getSoftStatusColors(statusText, baseColor)
                StatusChip(
                    text = statusText,
                    backgroundColor = chipBgColor,
                    contentColor = chipTextColor
                )
                Icon(
                    imageVector = if (section.isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = if (section.isCollapsed) "Expand job" else "Collapse job"
                )
            }
        }

        val lowEndMode = LocalLowEndMode.current
        if (lowEndMode.animationsDisabled) {
            if (!section.isCollapsed) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    section.group.items.forEachIndexed { index, resolvedItem ->
                        ToOrderItemRow(
                            zebraIndex = index,
                            jobFolderName = section.group.folderName,
                            resolvedItem = resolvedItem,
                            editable = editable,
                            onEditItem = onEditItem,
                            onToggleComplete = onToggleComplete
                        )
                    }
                }
            }
        } else {
            AnimatedVisibility(
                visible = !section.isCollapsed,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    section.group.items.forEachIndexed { index, resolvedItem ->
                        ToOrderItemRow(
                            zebraIndex = index,
                            jobFolderName = section.group.folderName,
                            resolvedItem = resolvedItem,
                            editable = editable,
                            onEditItem = onEditItem,
                            onToggleComplete = onToggleComplete
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToOrderItemRow(
    zebraIndex: Int,
    jobFolderName: String,
    resolvedItem: SpecialtyResolvedItem,
    editable: Boolean,
    onEditItem: (jobFolderName: String, item: SpecialtyResolvedItem) -> Unit,
    onToggleComplete: (jobFolderName: String, item: SpecialtyResolvedItem, completed: Boolean) -> Unit
) {
    val item = resolvedItem.item
    val status = toOrderStatusLabel(resolvedItem.isComplete)
    val tier = if (resolvedItem.isComplete) 7 else 6
    val baseColor = supplyStatusColor(tier)
    val (bandBgColor, bandTextColor) = getSoftStatusColors(status, baseColor)
    val cardText = toOrderItemCardText(item)
    val orderDate = cardText.orderDateLabel?.takeIf { resolvedItem.isComplete }

    SupplyTicketCard(
        bandLabel = status,
        bandTrailing = orderDate,
        bandBgColor = bandBgColor,
        bandTextColor = bandTextColor,
        zebraIndex = zebraIndex,
        // Sits inside the job's section card, so a lighter lift than the board.
        elevation = 2.dp,
        onClick = if (editable) ({ onEditItem(jobFolderName, resolvedItem) }) else null,
        onLongClick = if (editable) ({ onToggleComplete(jobFolderName, resolvedItem, !resolvedItem.isComplete) }) else null
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (editable) {
                IconButton(
                    onClick = { onToggleComplete(jobFolderName, resolvedItem, !resolvedItem.isComplete) }
                ) {
                    Icon(
                        imageVector = if (resolvedItem.isComplete) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (resolvedItem.isComplete) "Mark not ordered" else "Mark ordered",
                        tint = baseColor
                    )
                }
            } else {
                Icon(
                    imageVector = if (resolvedItem.isComplete) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (resolvedItem.isComplete) "Ordered" else "Not yet ordered",
                    tint = baseColor,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                cardText.cabinetLabel?.let { cabinetLabel ->
                    Text(
                        cabinetLabel.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    cardText.itemName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (cardText.quantityLabel != null || cardText.supportingText.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        cardText.quantityLabel?.let { SupplyQuantityPill(it.replace("Qty ", "×")) }
                        if (cardText.supportingText.isNotBlank()) {
                            Text(
                                cardText.supportingText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

internal data class ToOrderItemCardText(
    val cabinetLabel: String?,
    val itemName: String,
    val quantityLabel: String?,
    val orderDateLabel: String?,
    val supportingText: String
)

internal fun toOrderStatusLabel(isComplete: Boolean): String {
    return if (isComplete) "ORDERED" else "NOT ORDERED"
}

internal fun defaultToOrderDate(date: java.time.LocalDate): String {
    return date.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
}

internal fun toOrderItemCardText(item: SpecialtyItem): ToOrderItemCardText {
    val cabinetLabel = when {
        !item.cabinetLabel.isNullOrBlank() -> "Cab #${item.cabinetLabel}"
        item.cabinetNumbers.isNotEmpty() -> "Cab #${item.cabinetNumbers.joinToString(", ")}"
        else -> null
    }
    val quantityLabel = item.quantity?.let { quantity ->
        val label = if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
        "Qty $label"
    }
    val supportingText = listOfNotNull(
        item.dimensions?.takeIf { it.isNotBlank() },
        item.material?.takeIf { it.isNotBlank() },
        item.supplier?.takeIf { it.isNotBlank() }?.let { "Supplier: $it" },
        item.model?.takeIf { it.isNotBlank() }?.let { "Model: $it" }
    ).joinToString(" • ")

    return ToOrderItemCardText(
        cabinetLabel = cabinetLabel,
        itemName = item.name,
        quantityLabel = quantityLabel,
        orderDateLabel = item.orderDate?.takeIf { it.isNotBlank() },
        supportingText = supportingText
    )
}

@Composable
private fun ToOrderEditDialog(
    item: SpecialtyItem,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        cabinetNumbers: List<String>,
        supplier: String?,
        model: String?,
        orderDate: String?,
        tracking: String?,
        orderUrl: String?,
        notes: String?,
        quantity: Double?,
        material: String?,
        dimensions: String?
    ) -> Unit
) {
    var name by remember { mutableStateOf(item.name) }
    var cabinetNumbersStr by remember { mutableStateOf(item.cabinetNumbers.joinToString(", ")) }
    var supplier by remember { mutableStateOf(item.supplier.orEmpty()) }
    var model by remember { mutableStateOf(item.model.orEmpty()) }
    var orderDate by remember { mutableStateOf(item.orderDate.orEmpty()) }
    var tracking by remember { mutableStateOf(item.tracking.orEmpty()) }
    var orderUrl by remember { mutableStateOf(item.orderUrl.orEmpty()) }
    var notes by remember { mutableStateOf(item.notes.orEmpty()) }
    var quantityStr by remember { mutableStateOf(item.quantity?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()) }
    var material by remember { mutableStateOf(item.material.orEmpty()) }
    var dimensions by remember { mutableStateOf(item.dimensions.orEmpty()) }

    SupplyModalFrame(
        title = "Edit To Order Item",
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxHeight(0.85f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Item Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            OutlinedTextField(
                value = cabinetNumbersStr,
                onValueChange = { cabinetNumbersStr = it },
                label = { Text("Cabinet Numbers (comma separated)") },
                placeholder = { Text("e.g. 1, 2, 3") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = quantityStr,
                    onValueChange = { quantityStr = it },
                    label = { Text("Quantity") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = dimensions,
                    onValueChange = { dimensions = it },
                    label = { Text("Dimensions") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = material,
                    onValueChange = { material = it },
                    label = { Text("Material") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = supplier,
                    onValueChange = { supplier = it },
                    label = { Text("Supplier") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model Number") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = orderDate,
                    onValueChange = { orderDate = it },
                    label = { Text("Order Date") },
                    placeholder = { Text("MM-DD or any date") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            }

            OutlinedTextField(
                value = tracking,
                onValueChange = { tracking = it },
                label = { Text("Tracking Number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            OutlinedTextField(
                value = orderUrl,
                onValueChange = { orderUrl = it },
                label = { Text("Order URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                shape = MaterialTheme.shapes.medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val cabNums = cabinetNumbersStr.split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        val qty = quantityStr.toDoubleOrNull()
                        onSave(
                            name.trim(),
                            cabNums,
                            supplier.trim().ifBlank { null },
                            model.trim().ifBlank { null },
                            orderDate.trim().ifBlank { null },
                            tracking.trim().ifBlank { null },
                            orderUrl.trim().ifBlank { null },
                            notes.trim().ifBlank { null },
                            qty,
                            material.trim().ifBlank { null },
                            dimensions.trim().ifBlank { null }
                        )
                    },
                    enabled = name.isNotBlank()
                ) { Text("Save") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardCard(
    zebraIndex: Int = 0,
    item: SupplyItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    categoryName: String? = null
) {
    val tier = item.status.let { SUPPLY_STATUS_PRIORITY[it] } ?: 99
    val baseColor = supplyStatusColor(tier)
    val (bandBgColor, bandTextColor) = getSoftStatusColors(item.status, baseColor)
    val ageLabel = remember(item.statusAt) { supplyStatusAgeLabel(item.statusAt) }
    val quantity = item.fields["quantity"]?.takeIf { it.isNotBlank() }
    val sku = item.fields["sku"]?.takeIf { it.isNotBlank() }
    val notes = item.notes?.takeIf { it.isNotBlank() }?.replace("\n", " ")

    SupplyTicketCard(
        bandLabel = item.status,
        bandTrailing = ageLabel,
        bandBgColor = bandBgColor,
        bandTextColor = bandTextColor,
        zebraIndex = zebraIndex,
        onClick = onClick,
        onLongClick = onLongClick
    ) {
        if (!categoryName.isNullOrBlank()) {
            Text(
                text = categoryName.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (quantity != null || sku != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (quantity != null) SupplyQuantityPill("×$quantity")
                if (sku != null) {
                    Text(
                        text = sku,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (notes != null) {
            Text(
                text = notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * "Ticket" card shared by the supply board, Needs Attention and To Order pages: a status band
 * tinted with the tier color across the top, then [content] on a zebra-striped body.
 * Click handlers are optional so read-only callers (non-admin To Order) get no ripple.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SupplyTicketCard(
    bandLabel: String,
    bandTrailing: String?,
    bandBgColor: Color,
    bandTextColor: Color,
    zebraIndex: Int,
    modifier: Modifier = Modifier,
    elevation: Dp = 3.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val lowEnd = LocalLowEndMode.current
    val shape = RoundedCornerShape(10.dp)
    // Zebra tint composited onto surface stays fully opaque, so the external shadow can't
    // bleed through under the translucent status band (see CLAUDE.md "Frosted Glass Buttons").
    val cardColor = kkcZebraTint(zebraIndex).compositeOver(MaterialTheme.colorScheme.surface)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = if (lowEnd.shadowsDisabled) 0.dp else elevation, shape = shape, clip = false)
            .clip(shape)
            .background(cardColor)
            .then(
                if (lowEnd.shadowsDisabled) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                else Modifier
            )
            .then(
                if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bandBgColor)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = bandLabel.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = bandTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (bandTrailing != null) {
                Text(
                    text = bandTrailing,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = bandTextColor
                )
            }
        }
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

@Composable
private fun SupplyQuantityPill(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/**
 * Compact "time in current status" label for the kanban ticket band: "today" under a day,
 * otherwise whole days ("5d"). Null when [statusAt] is blank or not an ISO-8601 instant.
 */
internal fun supplyStatusAgeLabel(statusAt: String, now: java.time.Instant = java.time.Instant.now()): String? {
    if (statusAt.isBlank()) return null
    val at = runCatching { java.time.Instant.parse(statusAt) }.getOrNull() ?: return null
    val days = java.time.Duration.between(at, now).toDays()
    return if (days < 1) "today" else "${days}d"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryBoardColumn(
    category: SupplyCategory,
    items: List<SupplyItem>,
    headerColor: Color,
    subscriptionManager: SupplySubscriptionManager,
    subscriptionData: SupplySubscriptionData,
    onAddItem: () -> Unit,
    onOpenItem: (String) -> Unit,
    onLongPress: (SupplyItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val columnBgColor = MaterialTheme.colorScheme.surfaceVariant
    // Category header colors are the fixed muted palette (all dark enough for white text).
    val onHeader = Color.White
    val addColor = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val isSubscribed = subscriptionData.subscribedCategoryIds.contains(category.id)

    androidx.compose.material3.Card(
        modifier = modifier
            .fillMaxHeight()
            .wrapContentWidth()
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(8.dp), clip = false),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = columnBgColor)
    ) {
        CategoryColumnLayout(
            modifier = Modifier.fillMaxHeight(),
            header = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(headerColor)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = category.name.uppercase(),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = onHeader
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { scope.launch { subscriptionManager.toggleCategorySubscription(category.id) } },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isSubscribed) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                            contentDescription = if (isSubscribed) "Unsubscribe from category" else "Subscribe to category",
                            tint = onHeader.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            content = {
                FlowColumn(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (items.isEmpty()) {
                        Box(modifier = Modifier.width(300.dp)) {
                            Text(
                                text = "No items in this category.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    } else {
                        items.forEachIndexed { index, item ->
                            Box(modifier = Modifier.width(300.dp)) {
                                BoardCard(
                                    zebraIndex = index,
                                    item = item,
                                    onClick = { onOpenItem(item.id) },
                                    onLongClick = { onLongPress(item) }
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(300.dp)
                            .clickable { onAddItem() }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = addColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Add another card",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = addColor
                                )
                            )
                        }
                    }
                }
            }
        )
    }
}

private fun Modifier.verticalScrollbar(
    scrollState: androidx.compose.foundation.ScrollState,
    width: Dp = 4.dp,
    color: Color
): Modifier = this.drawWithContent {
    drawContent()
    val viewportHeight = size.height
    val totalHeight = scrollState.maxValue + viewportHeight
    if (totalHeight > viewportHeight && viewportHeight > 0) {
        val scrollbarHeight = (viewportHeight * viewportHeight) / totalHeight
        val scrollbarTop = (scrollState.value.toFloat() / totalHeight) * viewportHeight
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - width.toPx() - 2.dp.toPx(), scrollbarTop),
            size = Size(width.toPx(), scrollbarHeight),
            cornerRadius = CornerRadius(width.toPx() / 2, width.toPx() / 2)
        )
    }
}

@Composable
private fun CategoryColumnLayout(
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.ui.layout.Layout(
        content = {
            header()
            content()
        },
        modifier = modifier
    ) { measurables, constraints ->
        val headerMeasurable = measurables.getOrNull(0)
        val contentMeasurable = measurables.getOrNull(1)

        if (headerMeasurable == null || contentMeasurable == null) {
            return@Layout layout(0, 0) {}
        }

        // 1. Learn the header's (width-independent) height via intrinsics, without
        // consuming its one allowed measure() call.
        val headerHeightProbe = headerMeasurable.maxIntrinsicHeight(100_000)

        // 2. Measure content with the header's height subtracted so it doesn't overflow
        // past the bottom of the column.
        val contentPlaceable = contentMeasurable.measure(
            constraints.copy(
                minHeight = 0,
                maxHeight = (constraints.maxHeight - headerHeightProbe).coerceAtLeast(0)
            )
        )

        val columnWidth = contentPlaceable.width

        // 3. Re-measure the header pinned to the content's actual width.
        val headerPlaceable = headerMeasurable.measure(
            constraints.copy(
                minWidth = columnWidth,
                maxWidth = columnWidth,
                minHeight = 0
            )
        )

        val totalHeight = (headerPlaceable.height + contentPlaceable.height).coerceAtMost(constraints.maxHeight)
        layout(columnWidth, totalHeight) {
            headerPlaceable.placeRelative(0, 0)
            contentPlaceable.placeRelative(0, headerPlaceable.height)
        }
    }
}

private val HEADER_COLORS_LIGHT = listOf(
    Color(0xFF356A73), // slate-teal (original)
    Color(0xFF3D6B8E), // steel blue
    Color(0xFF4A6741), // muted sage green
    Color(0xFF6B5B8E), // dusty violet
    Color(0xFF7A5C4E), // warm terracotta
    Color(0xFF3D7A6B), // deep seafoam
    Color(0xFF5E6B3D), // olive
    Color(0xFF6B3D5B), // muted mauve
    Color(0xFF3D5B6B), // storm blue
    Color(0xFF6B6B3D), // golden moss
)

private val HEADER_COLORS_DARK = listOf(
    Color(0xFF2C5E66), // dark teal (original)
    Color(0xFF2E5A7A), // dark steel blue
    Color(0xFF3A5732), // dark sage green
    Color(0xFF574A7A), // dark dusty violet
    Color(0xFF6B4C3E), // dark terracotta
    Color(0xFF2E6A5A), // dark seafoam
    Color(0xFF4E5A32), // dark olive
    Color(0xFF6B2E4E), // dark mauve
    Color(0xFF2E4E6B), // dark storm blue
    Color(0xFF5A5A2E), // dark golden moss
)
