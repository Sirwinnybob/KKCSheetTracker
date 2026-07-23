package com.kkc.sheettracker.ui.standards

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.kkc.sheettracker.data.MoldingLibraryRepository
import com.kkc.sheettracker.data.models.MoldingLibraryItem
import com.kkc.sheettracker.data.models.MoldingUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Interactive full-screen molding detail overlay component.
 * Features top 2/3 profile vector preview with pinch-to-zoom/pan and theme-aware line rendering,
 * top-right controls for measurements and closing, and bottom 1/3 sheet listing job usage.
 */
@OptIn(ExperimentalSharedTransitionApi::class, FlowPreview::class)
@Composable
fun MoldingDetailOverlay(
    item: MoldingLibraryItem,
    repository: MoldingLibraryRepository,
    svgImageLoader: ImageLoader,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    isDarkPreview: Boolean = false,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    var showMeasurements by remember { mutableStateOf(true) }
    var svgData by remember(item.id, showMeasurements, isDarkPreview) { mutableStateOf<Any?>(null) }
    var usage by remember(item.id) { mutableStateOf<List<MoldingUsage>>(emptyList()) }
    var isLoadingUsage by remember(item.id) { mutableStateOf(true) }

    LaunchedEffect(item.id, showMeasurements, isDarkPreview) {
        svgData = withContext(Dispatchers.IO) {
            if (isDarkPreview) {
                repository.profileSvgBytes(item.category, item.fileId, showMeasurements, isDarkPreview = true)
            } else {
                repository.profileSvgFile(item.category, item.fileId, showMeasurements)
            }
        }
    }

    LaunchedEffect(item.id) {
        isLoadingUsage = true
        usage = withContext(Dispatchers.IO) { repository.fetchUsage(item.id) }
        isLoadingUsage = false
    }

    val isDark = isDarkPreview
    val previewBgColor = if (isDark) Color.Black else Color.White

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var detailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var renderedForScale by remember { mutableFloatStateOf(-1f) }

    // Re-rasterize the SVG at the settled zoom level so pinch-to-zoom stays sharp.
    // Mirrors the PDF viewer's debounced tile approach: graphicsLayer handles smooth
    // interaction, then a higher-res Bitmap replaces AsyncImage once the finger lifts.
    LaunchedEffect(svgData, containerSize) {
        detailBitmap = null
        renderedForScale = -1f
        if (svgData == null || containerSize == IntSize.Zero) return@LaunchedEffect
        snapshotFlow { scale }
            .debounce(150)
            .distinctUntilChanged { old, new -> abs(old - new) < 0.05f }
            .collectLatest { settledScale ->
                // Cap oversampling at 3× to keep bitmap area under ~16 MP.
                val oversample = settledScale.coerceIn(1f, 3f)
                var targetW = (containerSize.width * oversample).roundToInt().coerceAtLeast(1)
                var targetH = (containerSize.height * oversample).roundToInt().coerceAtLeast(1)
                val area = targetW.toLong() * targetH.toLong()
                if (area > 16_000_000L) {
                    val down = sqrt(area.toDouble() / 16_000_000.0).toFloat()
                    targetW = (targetW / down).roundToInt().coerceAtLeast(1)
                    targetH = (targetH / down).roundToInt().coerceAtLeast(1)
                }
                val w = targetW
                val h = targetH
                val bitmap = withContext(Dispatchers.IO) {
                    val request = ImageRequest.Builder(context)
                        .data(svgData)
                        .size(w, h)
                        .allowHardware(false)
                        .build()
                    val result = svgImageLoader.execute(request)
                    val drawable = (result as? SuccessResult)?.drawable ?: return@withContext null
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(if (isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    val canvas = AndroidCanvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    bmp
                } ?: return@collectLatest
                detailBitmap = bitmap
                renderedForScale = settledScale
            }
    }

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
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(previewBgColor)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
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
                val imageModifier = Modifier
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
                // Show the high-res detail bitmap once it's ready for the current zoom;
                // fall back to AsyncImage (base resolution) while the first render is pending.
                val snap = detailBitmap
                if (snap != null && !snap.isRecycled) {
                    Image(
                        bitmap = snap.asImageBitmap(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = imageModifier
                    )
                } else {
                    AsyncImage(
                        model = svgData,
                        contentDescription = item.name,
                        imageLoader = svgImageLoader,
                        contentScale = ContentScale.Fit,
                        colorFilter = null,
                        modifier = imageModifier
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
                        contentPadding = PaddingValues(bottom = 166.dp)
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
