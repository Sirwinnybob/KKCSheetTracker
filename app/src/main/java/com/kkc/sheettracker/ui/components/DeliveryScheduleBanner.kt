package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS
import com.kkc.sheettracker.data.models.DeliverySchedule
import java.time.DayOfWeek
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.theme.KKCSpacing
import java.time.LocalDate
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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

internal fun shouldShowDeliveryScheduleBanner(
    schedule: DeliverySchedule,
    showWhenEmpty: Boolean
): Boolean = showWhenEmpty || !schedule.isEmpty

internal fun totalDeliveryCount(schedule: DeliverySchedule): Int =
    schedule.slots.values.sumOf { it.jobs.size }

internal fun daysWithDeliveries(schedule: DeliverySchedule): Set<String> =
    DELIVERY_DAYS.filter { day ->
        DELIVERY_PERIODS.any { period -> schedule.slot(day, period).jobs.isNotEmpty() }
    }.toSet()

internal enum class DeliveryDayState { PAST, TODAY, FUTURE }

internal fun deliveryDayState(dayIndex: Int, today: DayOfWeek): DeliveryDayState {
    val todayIndex = today.value - 1 // DayOfWeek.MONDAY.value == 1 -> index 0
    return when {
        todayIndex !in DELIVERY_DAYS.indices -> DeliveryDayState.PAST // weekend: whole week is past
        dayIndex < todayIndex -> DeliveryDayState.PAST
        dayIndex == todayIndex -> DeliveryDayState.TODAY
        else -> DeliveryDayState.FUTURE
    }
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
    showWhenEmpty: Boolean = false
) {
    if (!shouldShowDeliveryScheduleBanner(schedule, showWhenEmpty)) return

    var bannerExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedDays by remember { mutableStateOf(daysWithDeliveries(schedule)) }
    var previousDeliveryDays by remember { mutableStateOf(daysWithDeliveries(schedule)) }
    LaunchedEffect(schedule) {
        val currentDeliveryDays = daysWithDeliveries(schedule)
        expandedDays = expandedDays + (currentDeliveryDays - previousDeliveryDays)
        previousDeliveryDays = currentDeliveryDays
    }
    val today = LocalDate.now().dayOfWeek
    val totalCount = remember(schedule) { totalDeliveryCount(schedule) }

    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.shadow(elevation = 2.dp, shape = RoundedCornerShape(9.dp), clip = false)
    ) {
        Row(Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.04f))) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
            Column(Modifier.weight(1f, fill = true)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { bannerExpanded = !bannerExpanded }
                        .padding(horizontal = KKCSpacing.l, vertical = KKCSpacing.inCardSpacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$totalCount — Deliveries This Week",
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
                        schedule = schedule,
                        today = today,
                        expandedDays = expandedDays,
                        onToggleDay = { day ->
                            expandedDays = if (day in expandedDays) expandedDays - day else expandedDays + day
                        }
                    )
                }
            }
        }
    }
}

private val DeliveryDaySegmentCollapsedWidth = 48.dp
private val DeliveryDaySegmentMinWidth = 140.dp
private val DeliveryDaySegmentMaxWidth = 260.dp

@Composable
private fun DeliveryDayStrip(
    schedule: DeliverySchedule,
    today: DayOfWeek,
    expandedDays: Set<String>,
    onToggleDay: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = KKCSpacing.l, vertical = KKCSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(KKCSpacing.xs)
    ) {
        DELIVERY_DAYS.forEachIndexed { dayIdx, day ->
            val dayLabel = day.replaceFirstChar { it.uppercase() }
            val dayCount = DELIVERY_PERIODS.sumOf { period -> schedule.slot(day, period).jobs.size }
            val state = deliveryDayState(dayIdx, today)
            DeliveryDaySegment(
                day = day,
                dayLabel = dayLabel,
                dayCount = dayCount,
                state = state,
                isExpanded = day in expandedDays,
                schedule = schedule,
                onToggle = { onToggleDay(day) }
            )
        }
    }
}

@Composable
private fun DeliveryDaySegment(
    day: String,
    dayLabel: String,
    dayCount: Int,
    state: DeliveryDayState,
    isExpanded: Boolean,
    schedule: DeliverySchedule,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val isToday = state == DeliveryDayState.TODAY
    val contentAlpha = if (state == DeliveryDayState.PAST) 0.45f else 1f
    val borderColor = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (isToday) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = Modifier
            .animateContentSize()
            .widthIn(
                min = if (isExpanded) DeliveryDaySegmentMinWidth else DeliveryDaySegmentCollapsedWidth,
                max = if (isExpanded) DeliveryDaySegmentMaxWidth else DeliveryDaySegmentCollapsedWidth
            )
            .fillMaxHeight()
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
            Column {
                Text(
                    text = buildString {
                        append(dayCount)
                        append(" — ")
                        append(dayLabel)
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
                    val slot = schedule.slot(day, period)
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
                                    context.startActivity(Intent(Intent.ACTION_VIEW, deliveryMapsUri(job.address)))
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(KKCSpacing.xxs))
                }
            }
        } else {
            Text(
                text = "$dayCount — $dayLabel",
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
    onOpenMaps: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = KKCSpacing.xxs)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = job.jobNumber.ifBlank { "(no job #)" },
                style = MaterialTheme.typography.bodySmall,
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
