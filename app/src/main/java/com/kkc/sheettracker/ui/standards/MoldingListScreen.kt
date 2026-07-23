package com.kkc.sheettracker.ui.standards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onOpenMolding: ((MoldingLibraryItem) -> Unit)? = null
) {
    val svgImageLoader = rememberSvgImageLoader()

    var library by remember { mutableStateOf(MoldingLibrary()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showMeasurements by remember { mutableStateOf(false) }
    var usageCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var expandedItem by remember { mutableStateOf<MoldingLibraryItem?>(null) }

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

    DisposableEffect(Unit) {
        onDispose {
            if (navBarDeco.owner == ownerId) {
                if (!navBarDeco.keepSearchDeco) {
                    navBarDeco.searchDecoration = null
                }
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

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = listBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(visible, key = { it.id }) { item ->
                        MoldingCard(
                            item = item,
                            repository = repository,
                            svgImageLoader = svgImageLoader,
                            showMeasurements = showMeasurements,
                            usageCount = usageCounts[item.id] ?: 0,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            isExpanded = expandedItem?.id == item.id,
                            onClick = {
                                expandedItem = item
                                onOpenMolding?.invoke(item)
                            }
                        )
                    }
                }
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
    onClick: () -> Unit
) {
    var svgFile by remember(item.id, showMeasurements) { mutableStateOf<File?>(null) }
    LaunchedEffect(item.id, showMeasurements) {
        svgFile = withContext(Dispatchers.IO) {
            repository.profileSvgFile(item.category, item.fileId, showMeasurements)
        }
    }

    val isDark = isSystemInDarkTheme()
    val previewBgColor = if (isDark) Color.Black else Color.White
    val imageColorFilter = if (isDark) ColorFilter.tint(Color.White) else null
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
                    model = svgFile,
                    contentDescription = item.name,
                    imageLoader = svgImageLoader,
                    contentScale = ContentScale.Fit,
                    colorFilter = imageColorFilter,
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
