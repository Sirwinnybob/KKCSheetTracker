package com.kkc.sheettracker.ui.standards

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.kkc.sheettracker.data.MoldingLibraryRepository
import com.kkc.sheettracker.data.models.MoldingLibraryItem
import com.kkc.sheettracker.data.models.MoldingUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Interactive full-screen molding detail overlay component.
 * Features top 2/3 profile vector preview with pinch-to-zoom/pan and theme-aware line rendering,
 * top-right controls for measurements and closing, and bottom 1/3 sheet listing job usage.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MoldingDetailOverlay(
    item: MoldingLibraryItem,
    repository: MoldingLibraryRepository,
    svgImageLoader: ImageLoader,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    var showMeasurements by remember { mutableStateOf(true) }
    var svgFile by remember(item.id, showMeasurements) { mutableStateOf<File?>(null) }
    var usage by remember(item.id) { mutableStateOf<List<MoldingUsage>>(emptyList()) }
    var isLoadingUsage by remember(item.id) { mutableStateOf(true) }

    LaunchedEffect(item.id, showMeasurements) {
        svgFile = withContext(Dispatchers.IO) {
            repository.profileSvgFile(item.category, item.fileId, showMeasurements)
        }
    }

    LaunchedEffect(item.id) {
        isLoadingUsage = true
        usage = withContext(Dispatchers.IO) { repository.fetchUsage(item.id) }
        isLoadingUsage = false
    }

    val isDark = isSystemInDarkTheme()
    val previewBgColor = if (isDark) Color.Black else Color.White
    val imageColorFilter = if (isDark) ColorFilter.tint(Color.White) else null

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    fun getMaxOffset(currentScale: Float): Pair<Float, Float> {
        val width = containerSize.width.toFloat().coerceAtLeast(1f)
        val height = containerSize.height.toFloat().coerceAtLeast(1f)
        val maxPanX = ((width * currentScale - width) / 2f).coerceAtLeast(0f)
        val maxPanY = ((height * currentScale - height) / 2f).coerceAtLeast(0f)
        return maxPanX to maxPanY
    }

    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        val (maxPanX, maxPanY) = getMaxOffset(nextScale)
        val appliedZoomChange = nextScale / scale
        val anchorX = centroid.x - containerSize.width / 2f
        val anchorY = centroid.y - containerSize.height / 2f
        val nextOffsetX = (offset.x * appliedZoomChange + panChange.x + anchorX * (1f - appliedZoomChange))
            .coerceIn(-maxPanX, maxPanX)
        val nextOffsetY = (offset.y * appliedZoomChange + panChange.y + anchorY * (1f - appliedZoomChange))
            .coerceIn(-maxPanY, maxPanY)
        scale = nextScale
        offset = Offset(nextOffsetX, nextOffsetY)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(previewBgColor)
    ) {
        // Top 2/3 Preview Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(2f)
                .onSizeChanged { containerSize = it }
                .background(previewBgColor)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            coroutineScope.launch {
                                scaleAnim.snapTo(scale)
                                offsetXAnim.snapTo(offset.x)
                                offsetYAnim.snapTo(offset.y)
                                val j1 = launch { scaleAnim.animateTo(1f, spring()) }
                                val j2 = launch { offsetXAnim.animateTo(0f, spring()) }
                                val j3 = launch { offsetYAnim.animateTo(0f, spring()) }
                                j1.join()
                                j2.join()
                                j3.join()
                                scale = 1f
                                offset = Offset.Zero
                            }
                        }
                    )
                }
                .transformable(state = transformState),
            contentAlignment = Alignment.Center
        ) {
            with(sharedTransitionScope) {
                AsyncImage(
                    model = svgFile,
                    contentDescription = item.name,
                    imageLoader = svgImageLoader,
                    contentScale = ContentScale.Fit,
                    colorFilter = imageColorFilter,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .graphicsLayer {
                            val targetScale = if (scaleAnim.isRunning) scaleAnim.value else scale
                            val targetOffsetX = if (offsetXAnim.isRunning) offsetXAnim.value else offset.x
                            val targetOffsetY = if (offsetYAnim.isRunning) offsetYAnim.value else offset.y

                            scaleX = targetScale
                            scaleY = targetScale
                            translationX = targetOffsetX
                            translationY = targetOffsetY
                        }
                        .sharedElement(
                            rememberSharedContentState(key = "molding-image-${item.id}"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                )
            }

            // Floating Top-Right Controls
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .shadow(4.dp, RoundedCornerShape(20.dp), clip = false)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Measurements",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Switch(
                        checked = showMeasurements,
                        onCheckedChange = { showMeasurements = it }
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .shadow(4.dp, CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Bottom 1/3 Content Sheet
        val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .shadow(8.dp, sheetShape, clip = false)
                .clip(sheetShape)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // Drag handle pill visual indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = item.category,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Used on ${usage.size} jobs",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                if (isLoadingUsage) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (usage.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No job usage recorded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(usage) { entry ->
                            MoldingUsageRow(entry)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoldingUsageRow(usage: MoldingUsage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = usage.job,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!usage.type.isNullOrBlank()) {
                Text(
                    text = usage.type,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (usage.estimatedFeet != null) {
            val feetText = if (usage.estimatedFeet % 1.0 == 0.0) {
                "${usage.estimatedFeet.toInt()} ft"
            } else {
                "${String.format(java.util.Locale.US, "%.2f", usage.estimatedFeet)} ft"
            }
            Text(
                text = feetText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
