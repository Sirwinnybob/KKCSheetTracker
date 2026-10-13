package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.DeliveryDayState
import com.kkc.sheettracker.data.DeliveryDayView
import com.kkc.sheettracker.data.DeliveryWeekView
import com.kkc.sheettracker.data.buildDeliveryWeek
import com.kkc.sheettracker.data.defaultDeliveryWeekStart
import com.kkc.sheettracker.data.shopToday
import com.kkc.sheettracker.data.weekendTodayDay
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS
import com.kkc.sheettracker.data.models.DeliverySchedule
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.theme.KKCSpacing
import java.time.LocalDate
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.kkc.sheettracker.data.models.DeliveryJob

internal fun shouldShowDeliveryScheduleBanner(week: DeliveryWeekView, showWhenEmpty: Boolean): Boolean =
    shouldShowDeliveryScheduleBanner(week, showWhenEmpty, weekendToday = null)

/** [weekendToday] is today's own delivery day on a Saturday/Sunday (see `weekendTodayDay`). */
internal fun shouldShowDeliveryScheduleBanner(
    week: DeliveryWeekView,
    showWhenEmpty: Boolean,
    weekendToday: DeliveryDayView?
): Boolean = showWhenEmpty || week.totalCount > 0 || (weekendToday?.count ?: 0) > 0

internal fun daysWithDeliveries(week: DeliveryWeekView): Set<LocalDate> =
    week.days.filter { it.count > 0 }.map { it.date }.toSet()

/** After HT's Saturday rollover `weekOf` is next Monday, so the banner is about next week. */
internal fun deliveryBannerTitle(week: DeliveryWeekView, today: LocalDate): String =
    deliveryBannerTitle(week, today, weekendToday = null)

internal fun deliveryBannerTitle(
    week: DeliveryWeekView,
    today: LocalDate,
    weekendToday: DeliveryDayView?
): String = when {
    weekendToday != null -> "Deliveries Today & Next Week"
    week.weekStart > today -> "Deliveries Next Week"
    else -> "Deliveries This Week"
}

private val DeliveryBannerSizeSpring: FiniteAnimationSpec<IntSize> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
private val DeliveryBannerFadeInTween = tween<Float>(220, delayMillis = 60, easing = FastOutSlowInEasing)
private val DeliveryBannerFadeOutTween = tween<Float>(180, easing = FastOutSlowInEasing)

/**
 * Collapsible, animated dropdown replacing the old always-expanded [DeliveryScheduleWidget]
 * grid. Collapsed: a single header row showing the week's total delivery count. Expanded:
 * a horizontally-scrollable strip of 5 day segments (see [DeliveryDayStrip]), each
 * independently expandable sideways.
 */
@Composable
fun DeliveryScheduleBanner(
    schedule: DeliverySchedule,
    isAdminMode: Boolean,
    onEditRequested: () -> Unit,
    modifier: Modifier = Modifier,
    showWhenEmpty: Boolean = false,
    onJobSelected: (folderName: String) -> Unit = {}
) {
    val today = shopToday()
    val week = remember(schedule, today) {
        buildDeliveryWeek(schedule, defaultDeliveryWeekStart(schedule, today), today, includeEmptyWeekend = false)
    }
    // Saturday/Sunday: HT's weekOf already points at next Monday, so a delivery dated today would
    // otherwise be invisible. It leads the strip, ahead of the coming week.
    val weekendToday = remember(schedule, today) { weekendTodayDay(schedule, today) }
    if (!shouldShowDeliveryScheduleBanner(week, showWhenEmpty, weekendToday)) return

    val deliveryDays = daysWithDeliveries(week) + listOfNotNull(weekendToday?.date)
    var bannerExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedDays by remember { mutableStateOf(deliveryDays) }
    var previousDeliveryDays by remember { mutableStateOf(deliveryDays) }
    LaunchedEffect(week, weekendToday) {
        val currentDeliveryDays = daysWithDeliveries(week) + listOfNotNull(weekendToday?.date)
        expandedDays = expandedDays + (currentDeliveryDays - previousDeliveryDays)
        previousDeliveryDays = currentDeliveryDays
    }

    val accentColor = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.shadow(elevation = 2.dp, shape = RoundedCornerShape(9.dp), clip = false)
    ) {
        // Accent stripe is drawn post-layout against this Column's own measured size (drawBehind),
        // not via a fillMaxHeight() sibling — that pattern doesn't reliably size against a subtree
        // containing animateContentSize() (see DeliveryDaySegment), so it's avoided entirely here.
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.04f))
                .drawBehind {
                    drawRect(color = accentColor, size = size.copy(width = 4.dp.toPx()))
                }
                .padding(start = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { bannerExpanded = !bannerExpanded }
                    .padding(horizontal = KKCSpacing.l, vertical = KKCSpacing.inCardSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${week.totalCount + (weekendToday?.count ?: 0)} — ${deliveryBannerTitle(week, today, weekendToday)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isAdminMode) {
                    IconButton(onClick = onEditRequested) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit delivery schedule")
                    }
                }
                Icon(
                    imageVector = if (bannerExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = if (bannerExpanded) "Collapse deliveries" else "Expand deliveries",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = bannerExpanded,
                enter = expandVertically(DeliveryBannerSizeSpring) + fadeIn(DeliveryBannerFadeInTween),
                exit = shrinkVertically(DeliveryBannerSizeSpring) + fadeOut(DeliveryBannerFadeOutTween)
            ) {
                DeliveryDayStrip(
                    week = week,
                    weekendToday = weekendToday,
                    expandedDays = expandedDays,
                    onToggleDay = { day ->
                        expandedDays = if (day in expandedDays) expandedDays - day else expandedDays + day
                    },
                    onJobSelected = onJobSelected
                )
            }
        }
    }
}

private val DeliveryDaySegmentCollapsedWidth = 48.dp
private val DeliveryDaySegmentExpandedWidth = 220.dp
// Fixed, not intrinsic — IntrinsicSize.Max + fillMaxHeight() proved unreliable on-device
// (confirmed twice: with and without animateContentSize() in the subtree, the whole day
// strip would balloon to fill the remaining screen height instead of matching content).
// A hardcoded height applied identically to every segment guarantees uniform row height
// with zero measurement risk; any day with unusually many jobs scrolls within the fixed
// box (see verticalScroll below) instead of being clipped.
private val DeliveryDaySegmentHeight = 200.dp
private val DeliveryDaySegmentWidthSpring: FiniteAnimationSpec<Dp> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

@Composable
private fun DeliveryDayStrip(
    week: DeliveryWeekView,
    weekendToday: DeliveryDayView?,
    expandedDays: Set<LocalDate>,
    onToggleDay: (LocalDate) -> Unit,
    onJobSelected: (folderName: String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = KKCSpacing.l, vertical = KKCSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(KKCSpacing.xs)
    ) {
        (listOfNotNull(weekendToday) + week.days).forEach { day ->
            DeliveryDaySegment(
                day = day,
                isExpanded = day.date in expandedDays,
                onToggle = { onToggleDay(day.date) },
                onJobSelected = onJobSelected
            )
        }
    }
}

@Composable
private fun DeliveryDaySegment(
    day: DeliveryDayView,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onJobSelected: (folderName: String) -> Unit
) {
    val context = LocalContext.current
    val state = day.state
    val dayCount = day.count
    val isToday = state == DeliveryDayState.TODAY
    val contentAlpha = if (state == DeliveryDayState.PAST) 0.45f else 1f
    val borderColor = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (isToday) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    // animateDpAsState (a plain Dp value) rather than animateContentSize() — the latter's
    // custom measurement node doesn't compose safely with the IntrinsicSize.Max height-matching
    // on the parent Row (confirmed on-device: caused the whole strip to balloon to screen height).
    val animatedWidth by animateDpAsState(
        targetValue = if (isExpanded) DeliveryDaySegmentExpandedWidth else DeliveryDaySegmentCollapsedWidth,
        animationSpec = DeliveryDaySegmentWidthSpring,
        label = "daySegmentWidth"
    )

    Box(
        modifier = Modifier
            .width(animatedWidth)
            .height(DeliveryDaySegmentHeight)
            .clip(MaterialTheme.shapes.medium)
            .background(containerColor.copy(alpha = contentAlpha))
            .border(
                width = if (isToday) 2.dp else 1.dp,
                color = borderColor.copy(alpha = contentAlpha),
                shape = MaterialTheme.shapes.medium
            )
            .clickable(onClick = onToggle)
            .padding(KKCSpacing.s)
    ) {
        if (isExpanded) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = buildString {
                        append(dayCount)
                        append(" — ")
                        append(day.label)
                        if (isToday) append(" — Today")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(KKCSpacing.xs))
                DELIVERY_PERIODS.forEach { period ->
                    val slot = day.slot(period)
                    Text(
                        text = period.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                    )
                    if (slot.jobs.isEmpty()) {
                        Text(
                            text = "No deliveries",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha * 0.7f)
                        )
                    } else {
                        slot.jobs.forEach { job ->
                            DeliveryBannerJobRow(
                                job = job,
                                contentAlpha = contentAlpha,
                                onOpenMaps = {
                                    openDeliveryMapsSafely(context, job.address)
                                },
                                onSelect = job.folderName.takeIf { it.isNotBlank() }
                                    ?.let { folderName -> { onJobSelected(folderName) } }
                            )
                        }
                    }
                    Spacer(Modifier.height(KKCSpacing.xxs))
                }
            }
        } else {
            Text(
                text = "$dayCount — ${day.label}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Visible,
                modifier = Modifier
                    .align(Alignment.Center)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight))
                        layout(placeable.height, placeable.width) {
                            placeable.place(
                                x = -(placeable.width - placeable.height) / 2,
                                y = -(placeable.height - placeable.width) / 2
                            )
                        }
                    }
                    .graphicsLayer { rotationZ = -90f }
            )
        }
    }
}

@Composable
private fun DeliveryBannerJobRow(
    job: DeliveryJob,
    contentAlpha: Float,
    onOpenMaps: () -> Unit,
    onSelect: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = KKCSpacing.xxs)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = contentAlpha))
            .let { base -> if (onSelect != null) base.clickable(onClick = onSelect) else base }
            .padding(horizontal = KKCSpacing.s, vertical = KKCSpacing.xs)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = job.jobNumber.ifBlank { "(no job #)" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (job.description.isNotBlank()) {
                Text(
                    text = job.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onSelect != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Jump to job",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.size(16.dp)
            )
        }
        if (job.address.isNotBlank()) {
            IconButton(onClick = onOpenMaps) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Open in Maps",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
