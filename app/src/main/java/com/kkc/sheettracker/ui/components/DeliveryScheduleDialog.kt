package com.kkc.sheettracker.ui.components

import android.content.ClipData
import com.kkc.sheettracker.ui.components.icons.ActionCopyIcon
import com.kkc.sheettracker.ui.components.icons.ActionSaveIcon
import com.kkc.sheettracker.ui.components.icons.LocationPinIcon
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.filled.ChevronLeft
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.kkc.sheettracker.data.DeliveryDayState
import com.kkc.sheettracker.data.DeliveryDayView
import com.kkc.sheettracker.data.DeliveryWeekView
import com.kkc.sheettracker.data.MAX_DELIVERY_JOBS_PER_DAY
import com.kkc.sheettracker.data.MAX_DELIVERY_JOBS_PER_LEGACY_SLOT
import com.kkc.sheettracker.data.buildDeliveryWeek
import com.kkc.sheettracker.data.defaultDeliveryWeekStart
import com.kkc.sheettracker.data.deliveryDayJobs
import com.kkc.sheettracker.data.shopToday
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySchedulePickerJob
import com.kkc.sheettracker.data.models.DeliverySlot
import com.kkc.sheettracker.ui.theme.KKCSpacing

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

private val DeliveryDateLabel: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("EEE M/d", java.util.Locale.US)
private const val DeliveryDropNoticeMillis = 3_000L

/** Why a job may or may not be placed on a day/half-day (see [deliveryDropAllowed]). */
internal enum class DropDecision { Allowed, DayFull, HalfDayFull, PastCutoff }

private fun DeliveryJob.sameDeliveryJob(other: DeliveryJob): Boolean =
    if (folderName.isNotBlank() && other.folderName.isNotBlank()) folderName.equals(other.folderName, ignoreCase = true)
    else jobNumber == other.jobNumber

/**
 * Whether a job may land in [targetKey] on [day]. The job moving does not count against its own
 * destination: pass [job] when known, otherwise [sourceKey] (the slot it is dragged from, null for
 * the pool) is used to recognise an AM <-> PM move inside the same day. A v1 ([isDated] false)
 * Hours Tracker also caps each half-day at [MAX_DELIVERY_JOBS_PER_LEGACY_SLOT].
 */
internal fun deliveryDropAllowed(
    schedule: DeliverySchedule,
    day: DeliveryDayView,
    targetKey: String,
    sourceKey: String?,
    isDated: Boolean,
    job: DeliveryJob? = null
): DropDecision {
    if (!day.acceptsPlacement) return DropDecision.PastCutoff
    val dayJobs = deliveryDayJobs(schedule, targetKey)
    val targetJobs = schedule.slotByKey(targetKey).jobs
    val movingOnDay = if (job != null) dayJobs.count { it.sameDeliveryJob(job) }
    else if (sourceKey == day.amKey || sourceKey == day.pmKey) 1 else 0
    if (dayJobs.size - movingOnDay >= MAX_DELIVERY_JOBS_PER_DAY) return DropDecision.DayFull
    if (!isDated) {
        val movingInTarget = if (job != null) targetJobs.count { it.sameDeliveryJob(job) }
        else if (sourceKey == targetKey) 1 else 0
        if (targetJobs.size - movingInTarget >= MAX_DELIVERY_JOBS_PER_LEGACY_SLOT) return DropDecision.HalfDayFull
    }
    return DropDecision.Allowed
}

/** The transient message for a rejected drop/add; null when [decision] is [DropDecision.Allowed]. */
internal fun deliveryDropNotice(decision: DropDecision): String? = when (decision) {
    DropDecision.Allowed -> null
    DropDecision.DayFull -> "That day already has $MAX_DELIVERY_JOBS_PER_DAY deliveries"
    DropDecision.HalfDayFull -> "That half-day already has $MAX_DELIVERY_JOBS_PER_LEGACY_SLOT deliveries"
    DropDecision.PastCutoff -> "Too far in the past"
}

/**
 * The week the dialog shows. [selectedWeek] is an absolute date the admin navigated to (null = the
 * default week), so the view does not jump when HT's weekOf rolls over on Saturday. A v1 schedule
 * has no week navigation and always shows its default week.
 */
internal fun resolveDeliveryWeekStart(schedule: DeliverySchedule, today: LocalDate, selectedWeek: LocalDate?): LocalDate {
    val default = defaultDeliveryWeekStart(schedule, today)
    if (!schedule.isDated) return default
    return selectedWeek?.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) ?: default
}

/** [target] as the new selection, or null (follow the default week) when it IS the default week. */
internal fun selectDeliveryWeek(target: LocalDate, defaultWeek: LocalDate): LocalDate? =
    target.takeIf { it != defaultWeek }

/** Reset clears the mirrored Mon-Fri week starting at [weekStart]; the button says exactly which dates. */
internal fun deliveryResetConfirmLabel(weekStart: LocalDate): String =
    "Confirm: clear ${weekStart.format(DeliveryDateLabel)} – ${weekStart.plusDays(4).format(DeliveryDateLabel)}"

/** Header status from facts, not a local counter: edits still running plus slots queued on this tablet. */
internal fun deliveryEditStatusText(inFlightEdits: Int, pendingSlotCount: Int): String? {
    val total = inFlightEdits + pendingSlotCount
    return if (total > 0) "$total edit(s) sending/queued" else null
}

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
    onPlaceJob: ((job: DeliveryJob, toSlot: String, fromSlot: String?) -> Unit)? = null,
    onRemoveJob: ((job: DeliveryJob, fromSlot: String) -> Unit)? = null,
    onReplaceSlot: ((slot: String, jobs: List<DeliveryJob>) -> Unit)? = null,
    onResetWeek: (() -> Unit)? = null,
    notices: List<String> = emptyList(),
    onDismissNotices: () -> Unit = {},
    pendingSlotKeys: Set<String> = emptySet(),
    /** Edits the caller has submitted that have not finished yet (sending, or waiting their turn). */
    inFlightEdits: Int = 0,
    /** Bumped by the caller after a refused/noticed edit so the optimistic local change is reverted to [schedule]. */
    revision: Int = 0
) {
    val context = LocalContext.current
    val today = shopToday()
    // Absolute date, null = follow the default week: a Saturday weekOf rollover must not shift the view.
    var selectedWeek by remember { mutableStateOf<LocalDate?>(null) }
    val defaultWeekStart = defaultDeliveryWeekStart(schedule, today)
    val weekStart = resolveDeliveryWeekStart(schedule, today, selectedWeek)
    var editableSchedule by remember(schedule, revision) { mutableStateOf(schedule) }
    var activeAddSlot by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var dropNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dropNotice) {
        if (dropNotice != null) {
            delay(DeliveryDropNoticeMillis)
            dropNotice = null
        }
    }

    var dragState by remember { mutableStateOf<DeliveryDragState?>(null) }
    var hoveredSlotKey by remember { mutableStateOf<String?>(null) }
    val slotBounds = remember { mutableStateMapOf<String, Rect>() }
    var rootPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    var dragOriginWindow by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(schedule, revision) {
        editableSchedule = schedule
        confirmReset = false
    }

    val week = remember(editableSchedule, weekStart, today, isAdminMode) {
        buildDeliveryWeek(editableSchedule, weekStart, today, includeEmptyWeekend = isAdminMode && editableSchedule.isDated)
    }
    /** Day of [slotKey] in the shown week, for the drop/add rules; null when that slot isn't on screen. */
    fun dayOfSlot(slotKey: String): DeliveryDayView? =
        week.days.firstOrNull { it.amKey == slotKey || it.pmKey == slotKey }

    /** Returns false (after telling the admin why) when the job may not go there. */
    fun placeJob(job: DeliveryJob, toSlot: String, fromSlot: String?): Boolean {
        val dest = editableSchedule.slotByKey(toSlot).jobs
        // Already in this slot: nothing to place (a drop onto its own slot, or a pool job already there).
        if (dest.any { it.sameDeliveryJob(job) }) return true
        val day = dayOfSlot(toSlot) ?: return false
        // The limit is per day (the moving job doesn't count against its own destination day); a v1
        // HT also caps each half-day at 3 and quarantines a request that exceeds it.
        val decision = deliveryDropAllowed(editableSchedule, day, toSlot, fromSlot, !editableSchedule.isDated, job)
        if (decision != DropDecision.Allowed) {
            dropNotice = deliveryDropNotice(decision)
            return false
        }
        var next = editableSchedule
        if (fromSlot != null) next = next.withSlot(fromSlot, next.slotByKey(fromSlot).jobs.filterNot { it == job })
        editableSchedule = next.withSlot(toSlot, dest + job)
        onPlaceJob?.invoke(job, toSlot, fromSlot)
        return true
    }

    fun removeJob(slotKey: String, index: Int) {
        val jobs = editableSchedule.slotByKey(slotKey).jobs
        val job = jobs.getOrNull(index) ?: return
        editableSchedule = editableSchedule.withSlot(slotKey, jobs.filterIndexed { i, _ -> i != index })
        onRemoveJob?.invoke(job, slotKey)
    }

    fun replaceSlot(slotKey: String, jobs: List<DeliveryJob>) {
        editableSchedule = editableSchedule.withSlot(slotKey, jobs)
        onReplaceSlot?.invoke(slotKey, jobs)
    }

    fun resetWeek() {
        onResetWeek?.invoke()
        confirmReset = false
        activeAddSlot = null
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
        // Bounds (window coordinates) are kept for every week ever shown; only the visible week's
        // cells may be hit, so a stale off-screen week's rect can never capture the drop.
        val visibleKeys = week.days.flatMapTo(HashSet()) { listOf(it.amKey, it.pmKey) }
        hoveredSlotKey = slotBounds.entries
            .firstOrNull { (key, rect) -> key in visibleKeys && rect.contains(pointerApprox) }?.key
    }

    fun endDrag() {
        val drag = dragState
        val target = hoveredSlotKey
        if (drag != null && target != null) {
            val fromSlot = drag.sourceSlotKey
            val fromIndex = drag.sourceIndex
            if (dayOfSlot(target) != null) {
                if (fromSlot != null && fromIndex != null) {
                    val sourceSlot = editableSchedule.slotByKey(fromSlot)
                    if (fromSlot != target && sourceSlot.jobs.getOrNull(fromIndex) == drag.job) {
                        placeJob(drag.job, target, fromSlot)
                    }
                } else {
                    placeJob(drag.job, target, null)
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
                            Text("Delivery Schedule")
                            Text(
                                text = buildString {
                                    append("Week of ${week.weekStart.format(java.time.format.DateTimeFormatter.ofPattern("EEE M/d", java.util.Locale.US))}")
                                    if (isAdminMode) {
                                        append(" · ")
                                        append(deliveryEditStatusText(inFlightEdits, pendingSlotKeys.size) ?: "Admin editing")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        if (schedule.isDated) {
                            IconButton(
                                onClick = { selectedWeek = selectDeliveryWeek(week.weekStart.minusWeeks(1), defaultWeekStart) },
                                enabled = week.canGoBack
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous week")
                            }
                            TextButton(onClick = { selectedWeek = null }, enabled = !week.isDefaultWeek) { Text("This week") }
                            IconButton(
                                onClick = { selectedWeek = selectDeliveryWeek(week.weekStart.plusWeeks(1), defaultWeekStart) },
                                enabled = week.canGoForward
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "Next week")
                            }
                        }
                        if (isAdminMode && onResetWeek != null && week.isDefaultWeek) {
                            if (confirmReset) {
                                TextButton(onClick = ::resetWeek) { Text(deliveryResetConfirmLabel(week.weekStart)) }
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
                    if (isAdminMode && pendingSlotKeys.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Text(
                                text = "Edits go to Hours Tracker right away when it's reachable. Slots marked Pending are queued on this tablet and apply within about 15 seconds of reconnecting.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(KKCSpacing.cardPaddingSmall)
                            )
                        }
                    }
                    if (notices.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Row(Modifier.padding(KKCSpacing.cardPaddingSmall), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    notices.forEach { notice ->
                                        Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                }
                                IconButton(onClick = onDismissNotices) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss messages")
                                }
                            }
                        }
                    }

                    DeliveryScheduleGrid(
                        week = week,
                        schedule = editableSchedule,
                        pendingSlotKeys = pendingSlotKeys,
                        // v2 jobs need a folderName to be dated; manual entry has none, so placement is drag-only.
                        allowManualAdd = !schedule.isDated,
                        // v1 HT rejects a 4th job in one half-day, so undated schedules cap each slot at 3.
                        isDated = schedule.isDated,
                        isAdminMode = isAdminMode,
                        activeAddSlot = activeAddSlot,
                        dragState = dragState,
                        hoveredSlotKey = hoveredSlotKey,
                        onStartAdd = { slotKey -> activeAddSlot = if (activeAddSlot == slotKey) null else slotKey },
                        onCancelAdd = { activeAddSlot = null },
                        onAddJob = { slotKey, job -> if (placeJob(job, slotKey, null)) activeAddSlot = null },
                        onSaveJob = { slotKey, index, job ->
                            replaceSlot(slotKey, editableSchedule.slotByKey(slotKey).jobs.mapIndexed { i, e -> if (i == index) job else e })
                        },
                        onRemoveJob = ::removeJob,
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

                // Transient: tells the admin why a drop/add did nothing. Pinned to the viewport (not the
                // scrolling column) so it is visible wherever the drag ended.
                dropNotice?.let { notice ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(KKCSpacing.screenHorizontal)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(KKCSpacing.cardPaddingSmall)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberScrollStateCompat() = androidx.compose.foundation.rememberScrollState()

@Composable
private fun DeliveryScheduleGrid(
    week: DeliveryWeekView,
    schedule: DeliverySchedule,
    pendingSlotKeys: Set<String>,
    allowManualAdd: Boolean,
    isDated: Boolean,
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
        val dayCount = week.days.size.coerceAtLeast(1)
        val totalGapWidth = KKCSpacing.l * (dayCount - 1)
        val idealColumnWidth = (maxWidth - totalGapWidth) / dayCount
        val needsScroll = idealColumnWidth < DeliveryMinColumnWidth
        val columnWidth = idealColumnWidth.coerceIn(DeliveryMinColumnWidth, DeliveryMaxColumnWidth)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (needsScroll) it.scrollShadowBleed().horizontalScroll(rememberScrollStateCompat()).padding(horizontal = ScrollShadowBleed) else it },
            horizontalArrangement = Arrangement.spacedBy(KKCSpacing.l)
        ) {
            week.days.forEach { day ->
                // Keyed by date so a day keeps its cell state (open Add panel, animations) when the
                // list of days changes (weekend columns appear/disappear, week navigation).
                key(day.date) {
                    DeliveryDayColumn(
                        day = day,
                        schedule = schedule,
                        pendingSlotKeys = pendingSlotKeys,
                        allowManualAdd = allowManualAdd,
                        isDated = isDated,
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
}

@Composable
private fun DeliveryDayColumn(
    day: DeliveryDayView,
    schedule: DeliverySchedule,
    pendingSlotKeys: Set<String>,
    allowManualAdd: Boolean,
    isDated: Boolean,
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
            text = day.label + if (day.state == DeliveryDayState.TODAY) " · Today" else "",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (day.state == DeliveryDayState.PAST) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = KKCSpacing.xs)
        )
        // The six-delivery limit belongs to the whole day, so the meter sits once under the day label.
        // A past day can't be edited, so its meter would only be noise.
        if (isAdminMode && day.state != DeliveryDayState.PAST) {
            DeliveryCapacityIndicator(
                count = day.count,
                max = MAX_DELIVERY_JOBS_PER_DAY,
                modifier = Modifier.padding(bottom = KKCSpacing.xs)
            )
        }
        DELIVERY_PERIODS.forEach { period ->
            val slotKey = day.slotKey(period)
            val slot = day.slot(period)
            val isHovered = hoveredSlotKey == slotKey
            // Day limit (6), v1 half-day limit (3) and the past cutoff all live in deliveryDropAllowed;
            // a full day still accepts a job already on it (AM <-> PM).
            val isValidDropTarget = dragState != null &&
                dragState.sourceSlotKey != slotKey &&
                deliveryDropAllowed(schedule, day, slotKey, dragState.sourceSlotKey, isDated, dragState.job) ==
                DropDecision.Allowed
            val canAdd = deliveryDropAllowed(schedule, day, slotKey, null, isDated) == DropDecision.Allowed
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
                acceptsPlacement = day.acceptsPlacement && allowManualAdd,
                canAdd = canAdd,
                isPending = slotKey in pendingSlotKeys,
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
    acceptsPlacement: Boolean = true,
    /** False once the whole day (AM + PM) holds the maximum; the cell itself has no limit. */
    canAdd: Boolean = true,
    isPending: Boolean = false,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isPending) {
                    Spacer(Modifier.width(KKCSpacing.xs))
                    Text(
                        text = "Pending",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(Modifier.weight(1f))
                if (isAdminMode) {
                    if (acceptsPlacement) {
                        FilledTonalIconButton(
                            onClick = onStartAdd,
                            enabled = canAdd
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add delivery",
                                modifier = Modifier.size(20.dp)
                            )
                        }
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
private fun DeliveryCapacityIndicator(count: Int, max: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics { contentDescription = "$count of $max deliveries" },
        horizontalArrangement = Arrangement.spacedBy(KKCSpacing.xxs)
    ) {
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
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)

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
                            // The gesture coroutine outlives recompositions; read the callbacks through
                            // updated state so a drop never runs against the schedule/week it started with.
                            detectDragGesturesAfterLongPress(
                                onDragStart = { currentOnDragStart(job, handleOrigin, slotKey, index) },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    currentOnDragMove(dragAmount)
                                },
                                onDragEnd = { currentOnDragEnd() },
                                onDragCancel = { currentOnDragCancel() }
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
                    label = { Text("Address, coordinates, or Plus Code") },
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
                        Icon(ActionSaveIcon, contentDescription = null)
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
            openDeliveryMapsSafely(context, address)
        }) {
            Icon(LocationPinIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(KKCSpacing.xxs))
            Text("Open in Maps")
        }
        TextButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("address", address))
        }) {
            Icon(ActionCopyIcon, contentDescription = null, modifier = Modifier.size(18.dp))
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
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onGloballyPositioned { coords -> chipOrigin = coords.positionInWindow() }
            .pointerInput(pickerJob) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart(job, chipOrigin, null, null) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOnDragMove(dragAmount)
                    },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragCancel() }
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
                label = { Text("Address, coordinates, or Plus Code") },
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
