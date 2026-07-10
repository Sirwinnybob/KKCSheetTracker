package com.kkc.sheettracker.ui.supply

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kkc.sheettracker.ui.components.ImmersiveDialogDecor

private val SupplyModalEnter: EnterTransition =
    fadeIn(tween(150)) + scaleIn(tween(170), initialScale = 0.96f) + slideInVertically(tween(170)) { it / 18 }

private val SupplyModalExit: ExitTransition =
    fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.98f) + slideOutVertically(tween(120)) { it / 24 }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplyModalFrame(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    headerTint: Color? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 160.dp.toPx() } }
    val deadZonePx = remember(density) { with(density) { 5.dp.toPx() } }
    var rawOffsetY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var isDismissing by remember { mutableStateOf(false) }
    var animationJob by remember { mutableStateOf<Job?>(null) }

    val offsetY = remember(rawOffsetY, thresholdPx, deadZonePx) {
        val raw = rawOffsetY
        val absRaw = kotlin.math.abs(raw)
        if (absRaw <= deadZonePx) {
            0f
        } else {
            val progress = if (thresholdPx > deadZonePx) {
                ((absRaw - deadZonePx) / (thresholdPx - deadZonePx)).coerceAtMost(1f)
            } else {
                0f
            }
            val multiplier = 0.08f + 0.92f * (progress * progress * progress)
            raw * multiplier
        }
    }

    val transitionState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }

    fun requestDismiss() {
        transitionState.targetState = false
    }

    LaunchedEffect(transitionState.currentState, transitionState.targetState, transitionState.isIdle) {
        if (transitionState.isIdle && !transitionState.currentState && !transitionState.targetState) {
            onDismiss()
        }
    }

    fun handleRelease() {
        val currentVal = rawOffsetY
        if (kotlin.math.abs(currentVal) <= deadZonePx) {
            rawOffsetY = 0f // Reset instantly without animating for taps/tiny movements
            return
        }

        if (currentVal != 0f && !isDismissing && (animationJob == null || !animationJob!!.isActive)) {
            val targetVal = if (currentVal > thresholdPx) {
                2000f
            } else if (currentVal < -thresholdPx) {
                -2000f
            } else {
                0f
            }

            if (targetVal != 0f) {
                isDismissing = true
                animationJob = scope.launch {
                    animate(
                        initialValue = currentVal,
                        targetValue = targetVal,
                        animationSpec = tween(durationMillis = 200)
                    ) { value, _ ->
                        rawOffsetY = value
                    }
                    onDismiss() // Dismiss immediately when animated off-screen to clear dialog background
                }
            } else {
                animationJob = scope.launch {
                    animate(
                        initialValue = currentVal,
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 150)
                    ) { value, _ ->
                        rawOffsetY = value
                    }
                }
            }
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isDismissing) return Offset.Zero
                if (source == NestedScrollSource.UserInput) {
                    animationJob?.cancel()
                    val delta = available.y
                    val currentOffset = rawOffsetY

                    if (currentOffset > 0f && delta < 0f) {
                        val newOffset = (currentOffset + delta).coerceAtLeast(0f)
                        rawOffsetY = newOffset
                        return Offset(0f, newOffset - currentOffset)
                    }
                    if (currentOffset < 0f && delta > 0f) {
                        val newOffset = (currentOffset + delta).coerceAtMost(0f)
                        rawOffsetY = newOffset
                        return Offset(0f, newOffset - currentOffset)
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (isDismissing) return Offset.Zero
                if (source == NestedScrollSource.UserInput) {
                    animationJob?.cancel()
                    val delta = available.y
                    if (delta != 0f) {
                        rawOffsetY += delta
                        return Offset(0f, delta)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                handleRelease()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                handleRelease()
                return Velocity.Zero
            }
        }
    }

    val gestureModifier = Modifier
        .pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { animationJob?.cancel() },
                onDragEnd = { handleRelease() },
                onDragCancel = { handleRelease() },
                onVerticalDrag = { change, dragAmount ->
                    if (!isDismissing) {
                        animationJob?.cancel()
                        change.consume()
                        rawOffsetY += dragAmount
                    }
                }
            )
        }

    Dialog(
        onDismissRequest = { requestDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler { requestDismiss() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visibleState = transitionState,
                enter = SupplyModalEnter,
                exit = SupplyModalExit
            ) {
                Surface(
                    modifier = modifier
                        .offset { IntOffset(0, offsetY.roundToInt()) }
                        .nestedScroll(nestedScrollConnection)
                        .then(gestureModifier)
                        .fillMaxWidth()
                        .widthIn(max = 1040.dp)
                        .fillMaxHeight(0.92f),
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 16.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    ImmersiveDialogDecor()
                    val topBarColor = headerTint
                        ?.copy(alpha = 0.16f)
                        ?.compositeOver(MaterialTheme.colorScheme.surface)
                        ?: Color.Transparent
                    Scaffold(
                        containerColor = Color.Transparent,
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                actions = {
                                    actions()
                                    IconButton(onClick = { requestDismiss() }) {
                                        Icon(Icons.Default.Close, contentDescription = "Close")
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = topBarColor,
                                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            if (headerTint != null) {
                                HorizontalDivider(
                                    thickness = 2.dp,
                                    color = headerTint.copy(alpha = 0.85f)
                                )
                            }
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            content = content
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SupplyPickerDialog(
    title: String,
    options: List<SupplyPickerOption>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    headerTint: Color? = null
) {
    SupplyModalFrame(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier.heightIn(max = 620.dp),
        headerTint = headerTint
    ) {
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(options, key = { it.id }) { option ->
                NavigationDrawerItem(
                    label = { Text(option.label) },
                    selected = option.selected,
                    onClick = option.onClick,
                    icon = option.icon,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}

data class SupplyPickerOption(
    val id: String,
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val icon: (@Composable () -> Unit)? = null
)
