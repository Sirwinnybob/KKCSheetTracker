package com.kkc.sheettracker.ui.standards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import coil.ImageLoader
import coil.compose.AsyncImage
import com.kkc.sheettracker.data.MoldingLibraryRepository
import com.kkc.sheettracker.data.models.MoldingLibrary
import com.kkc.sheettracker.data.models.MoldingLibraryItem
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only molding profile library: category tabs + a grid of profile cards.
 * Data comes from [MoldingLibraryRepository], which reads the cache Hours Tracker publishes
 * under `.metadata/moldings_cache/` — this screen never writes anything.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MoldingListScreen(
    repository: MoldingLibraryRepository,
    onBack: () -> Unit,
    onOpenMolding: ((MoldingLibraryItem) -> Unit)? = null,
    isDarkTheme: Boolean = false,
    useStandardSheets: Boolean = false
) {
    val isDarkPreview = MoldingLibraryScreenLogic.shouldUseDarkPreview(isDarkTheme, useStandardSheets)
    val svgImageLoader = rememberSvgImageLoader()

    var library by remember { mutableStateOf(MoldingLibrary()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showMeasurements by remember { mutableStateOf(false) }
    var usageCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var expandedItem by remember { mutableStateOf<MoldingLibraryItem?>(null) }
    var collapsedFrameGroups by remember { mutableStateOf(setOf<FrameStyleGroup>()) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { repository.fetchLibrary() }
        library = loaded
        selectedCategory = MoldingLibraryScreenLogic.defaultCategory(loaded)
        usageCounts = withContext(Dispatchers.IO) { repository.fetchUsageCounts() }
    }

    val navBarDeco = LocalNavBarDecoration.current
    val focusManager = LocalFocusManager.current
    val currentSearchQuery = searchQuery
    val ownerId = "standards_molding_list"

    SideEffect {
        if (expandedItem != null) {
            if (navBarDeco.owner == ownerId) {
                navBarDeco.searchDecoration = null
                navBarDeco.keepSearchDeco = false
            }
        } else {
            navBarDeco.owner = ownerId
            navBarDeco.searchDecoration = NavBarSearchDecoration(
                searchTextValue = currentSearchQuery,
                onSearchTextChange = { searchQuery = it },
                onGo = { focusManager.clearFocus() },
                isPartsEnabled = false,
                onParts = {},
                contextLine = if (currentSearchQuery.text.isNotBlank())
                    "Filtering moldings by \"${currentSearchQuery.text}\"" else "",
                placeholder = "Search moldings...",
                showParts = false,
                onScan = null
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (navBarDeco.owner == ownerId) {
                navBarDeco.searchDecoration = null
                navBarDeco.keepSearchDeco = false
                navBarDeco.owner = ""
            }
        }
    }

    val listBottomPadding = if (navBarDeco.searchDecoration != null) 172.dp else 16.dp

    SharedTransitionLayout {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                KKCTopAppBar(
                    title = { Text("Molding") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        Text("Measurements", modifier = Modifier.padding(end = 4.dp))
                        Switch(checked = showMeasurements, onCheckedChange = { showMeasurements = it })
                    }
                )

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(library.categories, key = { it }) { category ->
                        FilterChip(
                            selected = category == selectedCategory,
                            onClick = { selectedCategory = category },
                            label = { Text(category) }
                        )
                    }
                }

                val visible = MoldingLibraryScreenLogic.searchMoldings(
                    library = library,
                    selectedCategory = selectedCategory,
                    query = searchQuery.text
                )
                val isCrownBrowse = searchQuery.text.isBlank() &&
                    selectedCategory?.equals("Crown", ignoreCase = true) == true

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = listBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (isCrownBrowse) {
                        MoldingLibraryScreenLogic.crownFrameGroups(visible).forEach { (group, groupItems) ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "header-${group.name}") {
                                FrameStyleSectionHeader(
                                    group = group,
                                    count = groupItems.size,
                                    collapsed = group in collapsedFrameGroups,
                                    onToggle = {
                                        collapsedFrameGroups = if (group in collapsedFrameGroups)
                                            collapsedFrameGroups - group
                                        else
                                            collapsedFrameGroups + group
                                    }
                                )
                            }
                            if (group !in collapsedFrameGroups) {
                                items(groupItems, key = { it.id }) { item ->
                                    MoldingCard(
                                        item = item,
                                        repository = repository,
                                        svgImageLoader = svgImageLoader,
                                        showMeasurements = showMeasurements,
                                        usageCount = usageCounts[item.id] ?: 0,
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        isExpanded = expandedItem?.id == item.id,
                                        isDarkPreview = isDarkPreview,
                                        onClick = { expandedItem = item }
                                    )
                                }
                            }
                        }
                    } else {
                        items(visible, key = { it.id }) { item ->
                            MoldingCard(
                                item = item,
                                repository = repository,
                                svgImageLoader = svgImageLoader,
                                showMeasurements = showMeasurements,
                                usageCount = usageCounts[item.id] ?: 0,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                isExpanded = expandedItem?.id == item.id,
                                isDarkPreview = isDarkPreview,
                                onClick = { expandedItem = item }
                            )
                        }
                    }
                }
            }

            // Transparent input-blocker: sits between the list content (Z=0) and the
            // overlay (Z=2). Any tap that misses an interactive element in the overlay
            // — e.g. a near-miss on the × button landing on the Settings icon behind it
            // — is swallowed here before it can reach the underlying screen.
            if (expandedItem != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false).consume()
                            }
                        }
                )
            }

            AnimatedVisibility(
                visible = expandedItem != null,
                enter = fadeIn() + scaleIn(initialScale = 0.95f),
                exit = fadeOut() + scaleOut(targetScale = 0.95f)
            ) {
                expandedItem?.let { item ->
                    MoldingDetailOverlay(
                        item = item,
                        repository = repository,
                        svgImageLoader = svgImageLoader,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedVisibility,
                        isDarkPreview = isDarkPreview,
                        onDismiss = { expandedItem = null }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MoldingCard(
    item: MoldingLibraryItem,
    repository: MoldingLibraryRepository,
    svgImageLoader: ImageLoader,
    showMeasurements: Boolean,
    usageCount: Int,
    sharedTransitionScope: SharedTransitionScope? = null,
    isExpanded: Boolean = false,
    isDarkPreview: Boolean = false,
    onClick: () -> Unit
) {
    var svgData by remember(item.id, showMeasurements, isDarkPreview) { mutableStateOf<Any?>(null) }
    LaunchedEffect(item.id, showMeasurements, isDarkPreview) {
        svgData = withContext(Dispatchers.IO) {
            if (isDarkPreview) {
                repository.profileSvgBytes(item.category, item.fileId, showMeasurements, isDarkPreview = true)
            } else {
                repository.profileSvgFile(item.category, item.fileId, showMeasurements)
            }
        }
    }

    val isDark = isDarkPreview
    val previewBgColor = if (isDark) Color.Black else Color.White
    val cardShape = RoundedCornerShape(14.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, cardShape, clip = false)
            .clip(cardShape)
            .border(1.dp, borderColor, cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(previewBgColor),
                contentAlignment = Alignment.Center
            ) {
                val baseImageModifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)

                val imageModifier = if (sharedTransitionScope != null) {
                    with(sharedTransitionScope) {
                        baseImageModifier.sharedElementWithCallerManagedVisibility(
                            sharedContentState = rememberSharedContentState(key = "molding-image-${item.id}"),
                            visible = !isExpanded
                        )
                    }
                } else {
                    baseImageModifier
                }

                AsyncImage(
                    model = svgData,
                    contentDescription = item.name,
                    imageLoader = svgImageLoader,
                    contentScale = ContentScale.Fit,
                    colorFilter = null,
                    modifier = imageModifier
                )
            }

            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "Used on $usageCount jobs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FrameStyleSectionHeader(
    group: FrameStyleGroup,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(targetValue = if (collapsed) -90f else 0f, label = "chevron")
    Column(modifier = Modifier.fillMaxWidth().clickable { onToggle() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (collapsed) "Expand ${group.label}" else "Collapse ${group.label}",
                modifier = Modifier.rotate(rotation)
            )
            Text(
                text = "${group.label} · $count",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        HorizontalDivider()
    }
}
