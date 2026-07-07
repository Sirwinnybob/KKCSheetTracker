package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.JobLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import android.util.LruCache

// ---------------------------------------------------------------------------
// Board card state cache
// ---------------------------------------------------------------------------

private val cardStateCache = LruCache<String, CardState>(120)

fun clearBoardCardCache() {
    cardStateCache.evictAll()
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

data class JobBoardItem(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val labels: List<JobLabel> = emptyList()
)

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun JobBoardGrid(
    items: List<JobBoardItem>,
    jobRepository: JobRepository,
    onItemClick: (JobBoardItem) -> Unit,
    modifier: Modifier = Modifier,
    pendingItems: List<JobBoardItem> = emptyList(),
    columns: Int = 3,
    scanGeneration: Long = 0L
) {
    LaunchedEffect(scanGeneration) {
        clearBoardCardCache()
    }

    var expandedItem by remember { mutableStateOf<JobBoardItem?>(null) }

    SharedTransitionLayout(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Active Production section header (only when pending section exists)
                if (pendingItems.isNotEmpty()) {
                    item(span = { GridItemSpan(maxCurrentLineSpan) }) {
                        SectionHeader("Active Production")
                    }
                }
                items(items, key = { it.folderName }) { item ->
                    JobBoardCard(
                        item = item,
                        jobRepository = jobRepository,
                        onClick = { onItemClick(item) },
                        onLongClick = { expandedItem = item },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        expandedFolderName = expandedItem?.folderName
                    )
                }
                // Pending Delivery section
                if (pendingItems.isNotEmpty()) {
                    item(span = { GridItemSpan(maxCurrentLineSpan) }) {
                        SectionHeader("Pending Delivery")
                    }
                    items(pendingItems, key = { "pending_${it.folderName}" }) { item ->
                        JobBoardCard(
                            item = item,
                            jobRepository = jobRepository,
                            onClick = { onItemClick(item) },
                            onLongClick = { expandedItem = item },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            expandedFolderName = expandedItem?.folderName
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expandedItem != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                expandedItem?.let { item ->
                    val cachedThumbnail = remember(item.folderName) {
                        cardStateCache.get(item.folderName)?.thumbnail
                    }
                    CoverPageOverlay(
                        item = item,
                        thumbnail = cachedThumbnail,
                        jobRepository = jobRepository,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedVisibility,
                        onDismiss = { expandedItem = null }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

private enum class ConstructionType { FACE_FRAME, FRAMELESS, BOTH, UNKNOWN }

private data class CardState(
    val thumbnail: Bitmap?,
    val constructionType: ConstructionType
)

// Web-app-matching banner colours
private val bannerColorFaceFrame = Color(0xFF96B352)
private val bannerColorFrameless = Color(0xFFED9223)
private val bannerColorBoth      = Color(0xFF00858A)

internal val boardPlaceholderColors = listOf(
    Color(0xFF4285F4), // blue
    Color(0xFF34A853), // green
    Color(0xFFFBBC04), // yellow
    Color(0xFFEA4335), // red
    Color(0xFF9C27B0), // purple
    Color(0xFF00BCD4), // teal
    Color(0xFFFF5722), // deep orange
    Color(0xFF607D8B)  // blue-grey
)


// ---------------------------------------------------------------------------
// Card composable
// ---------------------------------------------------------------------------

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
private fun JobBoardCard(
    item: JobBoardItem,
    jobRepository: JobRepository,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    expandedFolderName: String?,
    modifier: Modifier = Modifier
) {
    val cachedState = remember(item.folderName) { cardStateCache.get(item.folderName) }
    var isLoading by remember(item.folderName) { mutableStateOf(cachedState == null) }

    // Load thumbnail + construction type together in one background pass
    val cardState by produceState<CardState?>(initialValue = cachedState, key1 = item.folderName) {
        if (cachedState != null) {
            isLoading = false
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            // --- thumbnail ---
            val filename = jobRepository.getJobPdfCatalog(item.folderName)
                .deliverySheet?.pdfFilename
            val thumbnail: Bitmap? = if (filename.isNullOrBlank()) null else {
                val file = jobRepository.getJobRootPdfFile(
                    item.folderName, filename, preferDarkMode = false
                ) ?: return@withContext CardState(null, resolveConstructionType(jobRepository, item.folderName))
                val engine = PdfRenderEngine(file)
                try { engine.renderThumbnail(pageIndex = 0, maxWidth = 600) }
                finally { engine.close() }
            }

            // --- construction type ---
            val type = resolveConstructionType(jobRepository, item.folderName)

            val state = CardState(thumbnail, type)
            cardStateCache.put(item.folderName, state)
            state
        }
        isLoading = false
    }

    val constructionType = cardState?.constructionType ?: ConstructionType.UNKNOWN
    val thumbnail = cardState?.thumbnail

    val (bannerBg, bannerLabel) = when (constructionType) {
        ConstructionType.FACE_FRAME -> bannerColorFaceFrame to "FACE FRAME"
        ConstructionType.FRAMELESS  -> bannerColorFrameless  to "FRAMELESS"
        ConstructionType.BOTH       -> bannerColorBoth        to "BOTH"
        ConstructionType.UNKNOWN    -> Color.Transparent     to ""
    }
    val showBanner = constructionType != ConstructionType.UNKNOWN || !isLoading

    val placeholderColor = remember(item.jobNumber) {
        boardPlaceholderColors[abs(item.jobNumber.hashCode()) % boardPlaceholderColors.size]
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column {
            // ── Coloured banner ──────────────────────────────────────────────
            if (showBanner) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (bannerBg == Color.Transparent) MaterialTheme.colorScheme.surfaceVariant else bannerBg)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.jobNumber,
                        color = if (bannerBg == Color.Transparent) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (bannerLabel.isNotEmpty()) {
                        Text(
                            text = bannerLabel,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            // ── Thumbnail / placeholder ──────────────────────────────────────
            Box {
                with(sharedTransitionScope) {
                    when {
                        thumbnail != null -> {
                            Image(
                                bitmap = thumbnail.asImageBitmap(),
                                contentDescription = "Cover sheet for ${item.jobNumber}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(11f / 8.5f)
                                    .sharedElementWithCallerManagedVisibility(
                                        sharedContentState = rememberSharedContentState(key = "cover:${item.folderName}"),
                                        visible = expandedFolderName != item.folderName
                                    )
                            )
                        }
                        isLoading -> {
                            // Reserve space while loading
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(11f / 8.5f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier
                                        .height(28.dp)
                                        .aspectRatio(1f)
                                )
                            }
                        }
                        else -> {
                            // No delivery sheet — coloured placeholder with job number
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(11f / 8.5f)
                                    .background(placeholderColor)
                                    .sharedElementWithCallerManagedVisibility(
                                        sharedContentState = rememberSharedContentState(key = "cover:${item.folderName}"),
                                        visible = expandedFolderName != item.folderName
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = item.jobNumber,
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                // Labels overlay — top-left corner over thumbnail
                if (item.labels.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        item.labels.forEach { label ->
                            Surface(
                                color = parseJobLabelColor(label.colorHex),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = label.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Job name footer ──────────────────────────────────────────────
            Text(
                text = item.jobName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Construction type resolution
// ---------------------------------------------------------------------------

private fun resolveConstructionType(
    jobRepository: JobRepository,
    folderName: String
): ConstructionType {
    val index = jobRepository.getCabinetSheetIndex(folderName) ?: return ConstructionType.UNKNOWN
    // The `mode` field on the assembly document is the authoritative construction type.
    // Values in the JSON are "FACE-FRAME", "FRAMELESS", "BOTH" (hyphen, not underscore).
    return when (index.documents.assembly.mode?.trim()?.uppercase()) {
        "FACE-FRAME" -> ConstructionType.FACE_FRAME
        "FRAMELESS"  -> ConstructionType.FRAMELESS
        "BOTH"       -> ConstructionType.BOTH
        else         -> ConstructionType.UNKNOWN
    }
}
