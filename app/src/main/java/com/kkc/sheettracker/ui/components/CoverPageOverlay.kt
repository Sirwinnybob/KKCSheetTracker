package com.kkc.sheettracker.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.data.JobRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CoverPageOverlay(
    item: JobBoardItem,
    thumbnail: Bitmap?,
    jobRepository: JobRepository,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onDismiss: () -> Unit
) {
    var highResBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoadingHighRes by remember { mutableStateOf(false) }

    val windowSize = LocalWindowInfo.current.containerSize
    val screenWidthPx = remember(windowSize) { windowSize.width }

    LaunchedEffect(item.folderName) {
        val filename = withContext(Dispatchers.IO) {
            jobRepository.getJobPdfCatalog(item.folderName).deliverySheet?.pdfFilename
        }
        if (!filename.isNullOrBlank()) {
            isLoadingHighRes = true
            val bitmap = withContext(Dispatchers.IO) {
                val file = jobRepository.getJobRootPdfFile(
                    item.folderName, filename, preferDarkMode = false
                ) ?: return@withContext null
                val engine = PdfRenderEngine(file)
                try {
                    engine.renderThumbnail(pageIndex = 0, maxWidth = screenWidthPx)
                } catch (e: Exception) {
                    null
                } finally {
                    engine.close()
                }
            }
            if (bitmap != null) {
                highResBitmap = bitmap
            }
            isLoadingHighRes = false
        }
    }

    BackHandler(onBack = onDismiss)

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // Tracked passively (PointerEventPass.Initial) on every pointer event so the
    // transformable() gesture below knows where the pinch is actually centered;
    // TransformableState's onTransformation callback only receives zoom/pan deltas,
    // never the centroid, so it can't tell where the fingers are on its own.
    var pinchCentroid by remember { mutableStateOf(Offset.Zero) }

    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val aspect = 0.773f

    fun getMaxOffset(currentScale: Float): Pair<Float, Float> {
        val screenWidth = screenWidthPx.toFloat()
        val screenHeight = screenWidth / aspect
        val maxPanX = ((screenWidth * currentScale - screenWidth) / 2f).coerceAtLeast(0f)
        val maxPanY = ((screenHeight * currentScale - screenHeight) / 2f).coerceAtLeast(0f)
        return maxPanX to maxPanY
    }

    val state = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        val (maxPanX, maxPanY) = getMaxOffset(nextScale)
        // Compensate for graphicsLayer's center-anchored scaling so the pinch
        // centroid stays under the fingers instead of the zoom always appearing
        // to originate from the view's center.
        val appliedZoomChange = nextScale / scale
        val anchorX = pinchCentroid.x - containerSize.width / 2f
        val anchorY = pinchCentroid.y - containerSize.height / 2f
        val nextOffsetX = (offset.x * appliedZoomChange + panChange.x * scale + anchorX * (1f - appliedZoomChange))
            .coerceIn(-maxPanX, maxPanX)
        val nextOffsetY = (offset.y * appliedZoomChange + panChange.y * scale + anchorY * (1f - appliedZoomChange))
            .coerceIn(-maxPanY, maxPanY)
        scale = nextScale
        offset = Offset(nextOffsetX, nextOffsetY)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .background(Color.Black.copy(alpha = 0.92f))
            .pointerInput(scale, offset) {
                detectTapGestures(
                    onDoubleTap = {
                        coroutineScope.launch {
                            launch { scaleAnim.animateTo(1f, spring()) }
                            launch { offsetXAnim.animateTo(0f, spring()) }
                            launch { offsetYAnim.animateTo(0f, spring()) }
                        }
                        scale = 1f
                        offset = Offset.Zero
                    }
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) {
                            pinchCentroid = event.calculateCentroid(useCurrent = true)
                        }
                    }
                }
            }
            .transformable(state = state),
        contentAlignment = Alignment.Center
    ) {
        val activeBitmap = highResBitmap ?: thumbnail
        val placeholderColor = remember(item.jobNumber) {
            boardPlaceholderColors[abs(item.jobNumber.hashCode()) % boardPlaceholderColors.size]
        }

        with(sharedTransitionScope) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
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
                        rememberSharedContentState(key = "cover:${item.folderName}"),
                        animatedVisibilityScope = animatedVisibilityScope
                    ),
                contentAlignment = Alignment.Center
            ) {
                Crossfade(targetState = activeBitmap, label = "highResCrossfade") { bmp ->
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Expanded Cover Sheet",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(placeholderColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = item.jobNumber,
                                color = Color.White,
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.4f), shape = RoundedCornerShape(50))
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White
            )
        }

        if (isLoadingHighRes) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 32.dp)
                    .size(24.dp)
            )
        }
    }
}
