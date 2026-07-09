package com.kkc.sheettracker.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.kkc.sheettracker.data.models.DELIVERY_DAY_LABELS
import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySchedulePickerJob
import com.kkc.sheettracker.data.models.DeliverySlot
import com.kkc.sheettracker.ui.theme.KKCSpacing
import java.net.URLEncoder

private val DeliverySizeSpring: FiniteAnimationSpec<IntSize> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
private val DeliveryFadeInTween: FiniteAnimationSpec<Float> =
    tween(220, delayMillis = 60, easing = FastOutSlowInEasing)
private val DeliveryFadeOutTween: FiniteAnimationSpec<Float> =
    tween(180, easing = FastOutSlowInEasing)
private val DeliveryPressScaleSpec: FiniteAnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
private val DeliveryHoverColorTween: FiniteAnimationSpec<Color> =
    tween(200, easing = FastOutSlowInEasing)
private val DeliveryMinColumnWidth = 140.dp
private val DeliveryMaxColumnWidth = 260.dp
private val DeliveryCellBorderWidth = 2.dp

/** sourceSlotKey/sourceIndex are null when the drag originated from the available-jobs pool. */
private data class DeliveryDragState(
    val sourceSlotKey: String?,
    val sourceIndex: Int?,
    val job: DeliveryJob,
    val dragOffset: Offset
)

@Composable
private fun Modifier.pressScale(onClick: () -> Unit = {}): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, DeliveryPressScaleSpec, label = "pressScale")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryScheduleDialog(
    schedule: DeliverySchedule,
    onDismiss: () -> Unit,
    isAdminMode: Boolean = false,
    availableJobs: List<DeliverySchedulePickerJob> = emptyList(),
    onQueueSlotEdit: ((slot: String, jobs: List<DeliveryJob>) -> Unit)? = null,
    onQueueReset: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var editableSchedule by remember(schedule) { mutableStateOf(schedule) }
    var activeAddSlot by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var pendingEdits by remember { mutableIntStateOf(0) }

    var dragState by remember { mutableStateOf<DeliveryDragState?>(null) }
    var hoveredSlotKey by remember { mutableStateOf<String?>(null) }
    val slotBounds = remember { mutableStateMapOf<String, Rect>() }
    var rootPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    var dragOriginWindow by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(schedule) {
        editableSchedule = schedule
        pendingEdits = 0
        confirmReset = false
    }

    fun queueSlot(slotKey: String, jobs: List<DeliveryJob>) {
        val clipped = jobs.take(MAX_DELIVERY_JOBS_PER_SLOT)
        editableSchedule = editableSchedule.withSlot(slotKey, clipped)
        onQueueSlotEdit?.invoke(slotKey, clipped)
        pendingEdits += 1
    }

    fun resetAll() {
        editableSchedule = DeliverySchedule()
        onQueueReset?.invoke()
        pendingEdits += 1
        confirmReset = false
        activeAddSlot = null
    }

    fun moveJob(fromSlotKey: String, fromIndex: Int, toSlotKey: String) {
        if (fromSlotKey == toSlotKey) return
        val sourceSlot = editableSchedule.slots[fromSlotKey] ?: DeliverySlot()
        val destSlot = editableSchedule.slots[toSlotKey] ?: DeliverySlot()
        if (destSlot.jobs.size >= MAX_DELIVERY_JOBS_PER_SLOT) return
        val job = sourceSlot.jobs.getOrNull(fromIndex) ?: return
        queueSlot(fromSlotKey, sourceSlot.jobs.filterIndexed { i, _ -> i != fromIndex })
        queueSlot(toSlotKey, destSlot.jobs + job)
    }

    fun startDrag(job: DeliveryJob, originInWindow: Offset, sourceSlotKey: String?, sourceIndex: Int?) {
        dragState = DeliveryDragState(sourceSlotKey, sourceIndex, job, Offset.Zero)
        dragOriginWindow = originInWindow
    }

    fun updateDrag(delta: Offset) {
        val current = dragState ?: return
        val updated = current.copy(dragOffset = current.dragOffset + delta)
        dragState = updated
        val pointerApprox = dragOriginWindow + updated.dragOffset
        hoveredSlotKey = slotBounds.entries.firstOrNull { (_, rect) -> rect.contains(pointerApprox) }?.key
    }

    fun endDrag() {
        val drag = dragState
        val target = hoveredSlotKey
        if (drag != null && target != null) {
            val fromSlot = drag.sourceSlotKey
            val fromIndex = drag.sourceIndex
            if (fromSlot != null && fromIndex != null) {
                val sourceSlot = editableSchedule.slots[fromSlot] ?: DeliverySlot()
                if (sourceSlot.jobs.getOrNull(fromIndex) == drag.job) {
                    moveJob(fromSlot, fromIndex, target)
                }
            } else {
                val destSlot = editableSchedule.slots[target] ?: DeliverySlot()
                if (destSlot.jobs.size < MAX_DELIVERY_JOBS_PER_SLOT) {
                    queueSlot(target, destSlot.jobs + drag.job)
                }
            }
        }
        dragState = null
        hoveredSlotKey = null
    }

    fun cancelDrag() {
        dragState = null
        hoveredSlotKey = null
    }

    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ImmersiveDialogDecor()
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("This Week's Delivery Schedule")
                            if (isAdminMode) {
                                Text(
                                    text = if (pendingEdits > 0) "$pendingEdits edit(s) queued" else "Admin editing",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        if (isAdminMode && onQueueReset != null) {
                            if (confirmReset) {
                                TextButton(onClick = ::resetAll) { Text("Confirm Reset") }
                                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
                            } else {
                                TextButton(onClick = { confirmReset = true }) { Text("Reset") }
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .onGloballyPositioned { rootPositionInWindow = it.positionInWindow() }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollStateCompat())
                        .padding(horizontal = KKCSpacing.screenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(KKCSpacing.xl)
                ) {
                    Spacer(Modifier.height(KKCSpacing.s))
                    if (isAdminMode && pendingEdits > 0) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Text(
                                text = "Queued edits will apply when Hours Tracker consumes this tablet's delivery schedule request, then this view will refresh from the master schedule.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(KKCSpacing.cardPaddingSmall)
                            )
                        }
                    }

                    DeliveryScheduleGrid(
                        schedule = editableSchedule,
                        isAdminMode = isAdminMode,
                        activeAddSlot = activeAddSlot,
                        dragState = dragState,
                        hoveredSlotKey = hoveredSlotKey,
                        onStartAdd = { slotKey -> activeAddSlot = if (activeAddSlot == slotKey) null else slotKey },
                        onCancelAdd = { activeAddSlot = null },
                        onAddJob = { slotKey, job ->
                            val slot = editableSchedule.slots[slotKey] ?: DeliverySlot()
                            if (slot.jobs.size < MAX_DELIVERY_JOBS_PER_SLOT) {
                                queueSlot(slotKey, slot.jobs + job)
                                activeAddSlot = null
                            }
                        },
                        onSaveJob = { slotKey, index, job ->
                            val slot = editableSchedule.slots[slotKey] ?: DeliverySlot()
                            queueSlot(slotKey, slot.jobs.mapIndexed { i, existing -> if (i == index) job else existing })
                        },
                        onRemoveJob = { slotKey, index ->
                            val slot = editableSchedule.slots[slotKey] ?: DeliverySlot()
                            queueSlot(slotKey, slot.jobs.filterIndexed { i, _ -> i != index })
                        },
                        onSlotBoundsChanged = { slotKey, rect -> slotBounds[slotKey] = rect },
                        onDragStart = ::startDrag,
                        onDragMove = ::updateDrag,
                        onDragEnd = ::endDrag,
                        onDragCancel = ::cancelDrag,
                        context = context
                    )

                    if (isAdminMode) {
                        DeliveryAvailableJobsPool(
                            availableJobs = availableJobs,
                            onDragStart = ::startDrag,
                            onDragMove = ::updateDrag,
                            onDragEnd = ::endDrag,
                            onDragCancel = ::cancelDrag
                        )
                    }
                    Spacer(Modifier.height(KKCSpacing.xl))
                }

                dragState?.let { drag ->
                    DeliveryJobGhostCard(
                        job = drag.job,
                        anchor = dragOriginWindow - rootPositionInWindow,
                        offset = drag.dragOffset
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberScrollStateCompat() = androidx.compose.foundation.rememberScrollState()

@Composable
private fun DeliveryScheduleGrid(
    schedule: DeliverySchedule,
    isAdminMode: Boolean,
    activeAddSlot: String?,
    context: Context,
    dragState: DeliveryDragState?,
    hoveredSlotKey: String?,
    onStartAdd: (String) -> Unit,
    onCancelAdd: () -> Unit,
    onAddJob: (String, DeliveryJob) -> Unit,
    onSaveJob: (String, Int, DeliveryJob) -> Unit,
    onRemoveJob: (String, Int) -> Unit,
    onSlotBoundsChanged: (String, Rect) -> Unit,
    onDragStart: (DeliveryJob, Offset, String?, Int?) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val dayCount = DELIVERY_DAYS.size
        val totalGapWidth = KKCSpacing.l * (dayCount - 1)
        val idealColumnWidth = (maxWidth - totalGapWidth) / dayCount
        val needsScroll = idealColumnWidth < DeliveryMinColumnWidth
        val columnWidth = idealColumnWidth.coerceIn(DeliveryMinColumnWidth, DeliveryMaxColumnWidth)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (needsScroll) it.horizontalScroll(rememberScrollStateCompat()) else it },
            horizontalArrangement = Arrangement.spacedBy(KKCSpacing.l)
        ) {
            DELIVERY_DAYS.forEachIndexed { dayIdx, day ->
                DeliveryDayColumn(
                    day = day,
                    dayLabel = DELIVERY_DAY_LABELS.getOrElse(dayIdx) { day.replaceFirstChar { it.uppercase() } },
                    schedule = schedule,
                    isAdminMode = isAdminMode,
                    activeAddSlot = activeAddSlot,
                    context = context,
                    dragState = dragState,
                    hoveredSlotKey = hoveredSlotKey,
                    columnWidth = if (needsScroll) DeliveryMinColumnWidth else columnWidth,
                    onStartAdd = onStartAdd,
                    onCancelAdd = onCancelAdd,
                    onAddJob = onAddJob,
                    onSaveJob = onSaveJob,
                    onRemoveJob = onRemoveJob,
                    onSlotBoundsChanged = onSlotBoundsChanged,
                    onDragStart = onDragStart,
                    onDragMove = onDragMove,
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel
                )
            }
        }
    }
}

@Composable
private fun DeliveryDayColumn(
    day: String,
    dayLabel: String,
    schedule: DeliverySchedule,
    isAdminMode: Boolean,
    activeAddSlot: String?,
    context: Context,
    dragState: DeliveryDragState?,
    hoveredSlotKey: String?,
    columnWidth: Dp,
    onStartAdd: (String) -> Unit,
    onCancelAdd: () -> Unit,
    onAddJob: (String, DeliveryJob) -> Unit,
    onSaveJob: (String, Int, DeliveryJob) -> Unit,
    onRemoveJob: (String, Int) -> Unit,
    onSlotBoundsChanged: (String, Rect) -> Unit,
    onDragStart: (DeliveryJob, Offset, String?, Int?) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    Column(modifier = Modifier.width(columnWidth)) {
        Text(
            text = dayLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = KKCSpacing.xs)
        )
        DELIVERY_PERIODS.forEach { period ->
            val slotKey = "${day}_${period}"
            val slot = schedule.slot(day, period)
            val isHovered = hoveredSlotKey == slotKey
            val isValidDropTarget = dragState != null &&
                dragState.sourceSlotKey != slotKey &&
                slot.jobs.size < MAX_DELIVERY_JOBS_PER_SLOT
            val dragSourceIndex = if (dragState?.sourceSlotKey == slotKey) dragState.sourceIndex else null
            DeliveryPeriodCell(
                slotKey = slotKey,
                period = period,
                slot = slot,
                context = context,
                isAdminMode = isAdminMode,
                activeAddSlot = activeAddSlot,
                isHovered = isHovered,
                isValidDropTarget = isValidDropTarget,
                dragSourceIndex = dragSourceIndex,
                onStartAdd = { onStartAdd(slotKey) },
                onCancelAdd = onCancelAdd,
                onAddJob = { job -> onAddJob(slotKey, job) },
                onSaveJob = { index, job -> onSaveJob(slotKey, index, job) },
                onRemoveJob = { index -> onRemoveJob(slotKey, index) },
                onBoundsChanged = { rect -> onSlotBoundsChanged(slotKey, rect) },
                onDragStart = onDragStart,
                onDragMove = onDragMove,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel,
                modifier = Modifier.padding(bottom = KKCSpacing.s)
            )
        }
    }
}

@Composable
private fun DeliveryPeriodCell(
    slotKey: String,
    period: String,
    slot: DeliverySlot,
    context: Context,
    isAdminMode: Boolean,
    activeAddSlot: String?,
    isHovered: Boolean,
    isValidDropTarget: Boolean,
    dragSourceIndex: Int?,
    onStartAdd: () -> Unit,
    onCancelAdd: () -> Unit,
    onAddJob: (DeliveryJob) -> Unit,
    onSaveJob: (Int, DeliveryJob) -> Unit,
    onRemoveJob: (Int) -> Unit,
    onBoundsChanged: (Rect) -> Unit,
    onDragStart: (DeliveryJob, Offset, String?, Int?) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            isHovered && isValidDropTarget -> MaterialTheme.colorScheme.primaryContainer
            isHovered && !isValidDropTarget -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = DeliveryHoverColorTween,
        label = "cellHoverColor"
    )
    val targetBorderColor = when {
        !isHovered -> Color.Transparent
        isValidDropTarget -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    val borderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = DeliveryHoverColorTween,
        label = "cellHoverBorder"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> onBoundsChanged(coords.boundsInWindow()) }
            .border(DeliveryCellBorderWidth, borderColor, MaterialTheme.shapes.medium),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(KKCSpacing.cardPaddingSmall)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = period.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (isAdminMode) {
                    DeliveryCapacityIndicator(count = slot.jobs.size, max = MAX_DELIVERY_JOBS_PER_SLOT)
                    Spacer(Modifier.width(KKCSpacing.xs))
                    FilledTonalIconButton(
                        onClick = onStartAdd,
                        enabled = slot.jobs.size < MAX_DELIVERY_JOBS_PER_SLOT
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add delivery",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(KKCSpacing.xs))
            AnimatedContent(targetState = slot.jobs.isEmpty(), label = "periodCellContent") { empty ->
                if (empty) {
                    DeliveryEmptySlotBox()
                } else {
                    Column {
                        slot.jobs.forEachIndexed { index, job ->
                            DeliveryJobCard(
                                job = job,
                                index = index,
                                slotKey = slotKey,
                                context = context,
                                isAdminMode = isAdminMode,
                                isDragSource = dragSourceIndex == index,
                                onSave = { onSaveJob(index, it) },
                                onRemove = { onRemoveJob(index) },
                                onDragStart = onDragStart,
                                onDragMove = onDragMove,
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragCancel
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = isAdminMode && activeAddSlot == slotKey,
                enter = expandVertically(DeliverySizeSpring) + fadeIn(DeliveryFadeInTween),
                exit = shrinkVertically(DeliverySizeSpring) + fadeOut(DeliveryFadeOutTween)
            ) {
                DeliveryAddJobPanel(
                    onAddJob = onAddJob,
                    onCancel = onCancelAdd
                )
            }
        }
    }
}

@Composable
private fun DeliveryCapacityIndicator(count: Int, max: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(KKCSpacing.xxs)) {
        repeat(max) { i ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = if (i < count) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun DeliveryEmptySlotBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(KKCSpacing.s),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No deliveries",
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun DeliveryJobCard(
    job: DeliveryJob,
    index: Int,
    slotKey: String,
    context: Context,
    isAdminMode: Boolean,
    isDragSource: Boolean,
    onSave: (DeliveryJob) -> Unit,
    onRemove: () -> Unit,
    onDragStart: (DeliveryJob, Offset, String?, Int?) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    var showDetail by remember { mutableStateOf(false) }
    var handleOrigin by remember { mutableStateOf(Offset.Zero) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = KKCSpacing.xs)
            .graphicsLayer { alpha = if (isDragSource) 0.4f else 1f }
            .pressScale(onClick = { showDetail = true }),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = KKCSpacing.l, vertical = KKCSpacing.s)
        ) {
            if (isAdminMode) {
                Icon(
                    imageVector = Icons.Default.DragIndicator,
                    contentDescription = "Drag to move",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .onGloballyPositioned { coords -> handleOrigin = coords.positionInWindow() }
                        .pointerInput(slotKey, index, job) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { onDragStart(job, handleOrigin, slotKey, index) },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDragMove(dragAmount)
                                },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragCancel() }
                            )
                        }
                )
                Spacer(Modifier.width(KKCSpacing.xs))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = job.jobNumber.ifBlank { "(no job #)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (job.description.isNotBlank()) {
                    Text(
                        text = job.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "View details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (showDetail) {
        DeliveryJobDetailSheet(
            job = job,
            isAdminMode = isAdminMode,
            context = context,
            onDismiss = { showDetail = false },
            onSave = { updated ->
                onSave(updated)
                showDetail = false
            },
            onRemove = {
                onRemove()
                showDetail = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliveryJobDetailSheet(
    job: DeliveryJob,
    isAdminMode: Boolean,
    context: Context,
    onDismiss: () -> Unit,
    onSave: (DeliveryJob) -> Unit,
    onRemove: () -> Unit
) {
    var editJobNumber by remember(job) { mutableStateOf(job.jobNumber) }
    var editDescription by remember(job) { mutableStateOf(job.description) }
    var editAddress by remember(job) { mutableStateOf(job.address) }
    val dirty = editJobNumber != job.jobNumber ||
        editDescription != job.description ||
        editAddress != job.address
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KKCSpacing.xl)
                .padding(bottom = KKCSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(KKCSpacing.s)
        ) {
            Text(
                text = if (isAdminMode) "Edit Delivery" else "Delivery Details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (isAdminMode) {
                OutlinedTextField(
                    value = editJobNumber,
                    onValueChange = { editJobNumber = it },
                    label = { Text("Job #") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editDescription,
                    onValueChange = { editDescription = it },
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editAddress,
                    onValueChange = { editAddress = it },
                    label = { Text("Address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (editAddress.isNotBlank()) {
                    DeliveryAddressActionsRow(address = editAddress, context = context)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(KKCSpacing.s)
                ) {
                    OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(KKCSpacing.xxs))
                        Text("Remove")
                    }
                    Button(
                        onClick = {
                            onSave(
                                job.copy(
                                    jobNumber = editJobNumber.trim(),
                                    description = editDescription.trim(),
                                    address = editAddress.trim()
                                )
                            )
                        },
                        enabled = dirty && editJobNumber.isNotBlank() && editDescription.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(KKCSpacing.xxs))
                        Text("Save")
                    }
                }
            } else {
                Text(
                    text = job.jobNumber,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (job.description.isNotBlank()) {
                    Text(
                        text = job.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (job.address.isNotBlank()) {
                    Text(
                        text = job.address,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = KKCSpacing.xs)
                    )
                    DeliveryAddressActionsRow(address = job.address, context = context)
                }
            }
        }
    }
}

@Composable
private fun DeliveryAddressActionsRow(address: String, context: Context) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            val encoded = URLEncoder.encode(address, "UTF-8")
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")))
        }) {
            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(KKCSpacing.xxs))
            Text("Open in Maps")
        }
        TextButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("address", address))
        }) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(KKCSpacing.xxs))
            Text("Copy")
        }
    }
}

@Composable
private fun DeliveryJobGhostCard(job: DeliveryJob, anchor: Offset, offset: Offset) {
    Card(
        modifier = Modifier
            .graphicsLayer {
                translationX = anchor.x + offset.x
                translationY = anchor.y + offset.y
                scaleX = 1.03f
                scaleY = 1.03f
            }
            .width(200.dp)
            .zIndex(10f),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(Modifier.padding(KKCSpacing.s)) {
            Text(job.jobNumber, fontWeight = FontWeight.Bold)
            Text(
                job.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DeliveryAvailableJobsPool(
    availableJobs: List<DeliverySchedulePickerJob>,
    onDragStart: (DeliveryJob, Offset, String?, Int?) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    if (availableJobs.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Available Jobs — drag onto a slot",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = KKCSpacing.s)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(KKCSpacing.s),
            verticalArrangement = Arrangement.spacedBy(KKCSpacing.s)
        ) {
            availableJobs.forEach { pickerJob ->
                DeliveryPoolJobChip(
                    pickerJob = pickerJob,
                    onDragStart = onDragStart,
                    onDragMove = onDragMove,
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel
                )
            }
        }
    }
}

@Composable
private fun DeliveryPoolJobChip(
    pickerJob: DeliverySchedulePickerJob,
    onDragStart: (DeliveryJob, Offset, String?, Int?) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val job = remember(pickerJob) {
        DeliveryJob(
            jobNumber = pickerJob.jobNumber,
            description = pickerJob.description,
            folderName = pickerJob.folderName
        )
    }
    var chipOrigin by remember { mutableStateOf(Offset.Zero) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onGloballyPositioned { coords -> chipOrigin = coords.positionInWindow() }
            .pointerInput(pickerJob) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart(job, chipOrigin, null, null) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragMove(dragAmount)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() }
                )
            }
            .padding(horizontal = KKCSpacing.s, vertical = KKCSpacing.xs)
    ) {
        Icon(
            imageVector = Icons.Default.DragIndicator,
            contentDescription = "Drag to schedule",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(KKCSpacing.xxs))
        Column {
            Text(
                text = "#${pickerJob.jobNumber}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = pickerJob.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp)
            )
        }
    }
}

@Composable
private fun DeliveryAddJobPanel(
    onAddJob: (DeliveryJob) -> Unit,
    onCancel: () -> Unit
) {
    var manualJobNumber by remember { mutableStateOf("") }
    var manualDescription by remember { mutableStateOf("") }
    var manualAddress by remember { mutableStateOf("") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = KKCSpacing.m),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(KKCSpacing.cardPaddingSmall),
            verticalArrangement = Arrangement.spacedBy(KKCSpacing.s)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Add Delivery",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
            }
            OutlinedTextField(
                value = manualJobNumber,
                onValueChange = { manualJobNumber = it },
                label = { Text("Job #") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = manualDescription,
                onValueChange = { manualDescription = it },
                label = { Text("Description") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = manualAddress,
                onValueChange = { manualAddress = it },
                label = { Text("Address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    onAddJob(
                        DeliveryJob(
                            jobNumber = manualJobNumber.trim(),
                            description = manualDescription.trim(),
                            address = manualAddress.trim()
                        )
                    )
                },
                enabled = manualJobNumber.isNotBlank() && manualDescription.isNotBlank(),
                contentPadding = PaddingValues(horizontal = KKCSpacing.l, vertical = KKCSpacing.s),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add")
            }
        }
    }
}

private fun DeliverySchedule.withSlot(slotKey: String, jobs: List<DeliveryJob>): DeliverySchedule =
    copy(slots = slots + (slotKey to DeliverySlot(jobs = jobs)))

private const val MAX_DELIVERY_JOBS_PER_SLOT = 3
