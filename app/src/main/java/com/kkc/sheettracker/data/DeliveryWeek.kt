package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import com.kkc.sheettracker.data.models.datedSlotKey
import com.kkc.sheettracker.data.models.isWeekdaySlotKey
import com.kkc.sheettracker.data.models.parseDatedSlotKey
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Hours Tracker keys every delivery date in the shop's calendar, never UTC or the tablet's zone. */
val SHOP_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

/** The shop delivers at most this many jobs per DAY; the AM/PM half-day is optional and uncapped on its own. */
const val MAX_DELIVERY_JOBS_PER_DAY = 6

/**
 * A v1 (undated, weekday-keyed) Hours Tracker rejects more than this many jobs in ONE half-day
 * slot and quarantines the whole request file, so undated schedules keep this cap too.
 */
const val MAX_DELIVERY_JOBS_PER_LEGACY_SLOT = 3

/**
 * Every job in the whole day of [slotKey] (its AM plus PM slots). A dated key covers that date's two
 * slots, a weekday key that weekday's two slots, any other key just its own slot.
 */
fun deliveryDayJobs(schedule: DeliverySchedule, slotKey: String): List<DeliveryJob> {
    val dated = parseDatedSlotKey(slotKey)
    val keys = when {
        dated != null -> listOf(datedSlotKey(dated.date, "am"), datedSlotKey(dated.date, "pm"))
        isWeekdaySlotKey(slotKey) -> slotKey.substringBefore('_').let { listOf("${it}_am", "${it}_pm") }
        else -> listOf(slotKey)
    }
    return keys.flatMap { schedule.slotByKey(it).jobs }
}

/** HT drops dated slots older than this from the file, so placing there would make a job vanish. */
const val DELIVERY_HISTORY_DAYS = 14L

private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE M/d", Locale.US)

fun shopToday(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.now(clock.withZone(SHOP_ZONE))

/** HT's `weekOf` rule: this Monday on Mon-Fri, NEXT Monday from Saturday 00:00 through Sunday. */
fun mirrorWeekOf(today: LocalDate): LocalDate = when (today.dayOfWeek) {
    DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    else -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

/**
 * The week the UI opens on. v2: the file's `weekOf` (or HT's rule when missing). v1: the week
 * containing today, which reproduces the old behavior (on a weekend the whole week is past).
 */
fun defaultDeliveryWeekStart(schedule: DeliverySchedule, today: LocalDate): LocalDate =
    if (schedule.isDated) schedule.weekOf ?: mirrorWeekOf(today)
    else today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

enum class DeliveryDayState { PAST, TODAY, FUTURE }

data class DeliveryDayView(
    val date: LocalDate,
    val label: String,
    val am: DeliverySlot,
    val pm: DeliverySlot,
    val amKey: String,
    val pmKey: String,
    val state: DeliveryDayState,
    val acceptsPlacement: Boolean
) {
    fun slot(period: String): DeliverySlot = if (period == "pm") pm else am
    fun slotKey(period: String): String = if (period == "pm") pmKey else amKey
    val count: Int get() = am.jobs.size + pm.jobs.size
}

data class DeliveryWeekView(
    val weekStart: LocalDate,
    val days: List<DeliveryDayView>,
    val isDefaultWeek: Boolean,
    val canGoBack: Boolean,
    val canGoForward: Boolean
) {
    val totalCount: Int get() = days.sumOf { it.count }
}

/**
 * One day's view, shared by [buildDeliveryWeek] and [weekendDays]. A dated schedule keys the
 * day by [date]; a v1 schedule keys it by [weekdayName] (`monday`...), which must then be non-null.
 */
private fun buildDeliveryDay(
    schedule: DeliverySchedule,
    date: LocalDate,
    weekdayName: String?,
    today: LocalDate
): DeliveryDayView {
    val amKey: String
    val pmKey: String
    if (schedule.isDated) {
        amKey = datedSlotKey(date, "am")
        pmKey = datedSlotKey(date, "pm")
    } else {
        requireNotNull(weekdayName) { "v1 delivery day needs a weekday name" }
        amKey = "${weekdayName}_am"
        pmKey = "${weekdayName}_pm"
    }
    return DeliveryDayView(
        date = date,
        label = date.format(DAY_LABEL),
        am = schedule.slotByKey(amKey),
        pm = schedule.slotByKey(pmKey),
        amKey = amKey,
        pmKey = pmKey,
        state = when {
            date < today -> DeliveryDayState.PAST
            date == today -> DeliveryDayState.TODAY
            else -> DeliveryDayState.FUTURE
        },
        acceptsPlacement = !schedule.isDated || date >= today.minusDays(DELIVERY_HISTORY_DAYS)
    )
}

/** Longest stretch [weekendDays] scans, so a corrupt far-future `weekOf` cannot make it loop for long. */
private const val WEEKEND_SCAN_DAYS = 14

/**
 * On Saturday/Sunday HT's `weekOf` is the coming Monday, so a delivery dated today sits in the week
 * BEFORE `weekOf` and the week view never shows it. Returns the weekend days (Sat/Sun) that have at
 * least one job, from today up to (not including) [weekStart]; on Saturday that includes Sunday.
 * Empty unless the schedule is dated and today is a Saturday or Sunday.
 *
 * [weekStart] is the Monday of the week the banner displays. When today falls inside that Mon-Sun
 * week (HT has not rewritten `weekOf` yet) the week view already shows today and a second copy here
 * would duplicate the day. When `weekOf` is stale by more than a week (today is after the displayed
 * week) the week view cannot show today either, so today (and Sunday, from a Saturday) is returned.
 */
fun weekendDays(schedule: DeliverySchedule, today: LocalDate, weekStart: LocalDate): List<DeliveryDayView> {
    if (!schedule.isDated) return emptyList()
    if (today.dayOfWeek != DayOfWeek.SATURDAY && today.dayOfWeek != DayOfWeek.SUNDAY) return emptyList()
    if (today in weekStart..weekStart.plusDays(6)) return emptyList()
    val endExclusive = if (today < weekStart) weekStart
    else today.plusDays(if (today.dayOfWeek == DayOfWeek.SATURDAY) 2 else 1)
    return generateSequence(today) { it.plusDays(1) }
        .takeWhile { it < endExclusive }
        .take(WEEKEND_SCAN_DAYS)
        .filter { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
        .map { buildDeliveryDay(schedule, it, null, today) }
        .filter { it.count > 0 }
        .toList()
}

/** Today's own entry from [weekendDays], or null when today has no job (or is not a hidden weekend day). */
fun weekendTodayDay(schedule: DeliverySchedule, today: LocalDate, weekStart: LocalDate): DeliveryDayView? =
    weekendDays(schedule, today, weekStart).firstOrNull { it.date == today }

/** The UI never browses further ahead than this many weeks past the default week. */
const val DELIVERY_FORWARD_WEEKS = 52L

fun buildDeliveryWeek(
    schedule: DeliverySchedule,
    weekStart: LocalDate,
    today: LocalDate,
    includeEmptyWeekend: Boolean
): DeliveryWeekView {
    val monday = weekStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val defaultStart = defaultDeliveryWeekStart(schedule, today)
    val days = (0L until 7L).mapNotNull { offset ->
        val date = monday.plusDays(offset)
        val weekend = offset >= 5
        if (!schedule.isDated && weekend) return@mapNotNull null
        val day = buildDeliveryDay(schedule, date, DELIVERY_DAYS.getOrNull(offset.toInt()), today)
        if (weekend && !includeEmptyWeekend && day.count == 0) return@mapNotNull null
        day
    }
    return DeliveryWeekView(
        weekStart = monday,
        days = days,
        isDefaultWeek = monday == defaultStart,
        canGoBack = schedule.isDated && monday.minusDays(1) >= today.minusDays(DELIVERY_HISTORY_DAYS),
        canGoForward = schedule.isDated && monday.plusWeeks(1) <= defaultStart.plusWeeks(DELIVERY_FORWARD_WEEKS)
    )
}

/**
 * The slot a queued edit actually lands on. On a v2 (dated) schedule HT dates a weekday-key edit
 * onto the mirrored week, so `tuesday_am` becomes `<weekOf + 1>_am` (HT's rule when `weekOf` is
 * missing); a v1 schedule keeps weekday keys. Null for any key HT would not accept.
 */
private fun resolvePendingSlotKey(slot: String, schedule: DeliverySchedule, today: LocalDate): String? {
    if (parseDatedSlotKey(slot) != null) return slot
    if (!isWeekdaySlotKey(slot)) return null
    if (!schedule.isDated) return slot
    val dayIndex = DELIVERY_DAYS.indexOf(slot.substringBefore('_'))
    val period = slot.substringAfter('_')
    return datedSlotKey((schedule.weekOf ?: mirrorWeekOf(today)).plusDays(dayIndex.toLong()), period)
}

/**
 * Shows this tablet's own queued (not yet consumed) request on top of the published schedule.
 * HT applies `resetAll` first (clears the mirrored Mon-Fri week only: `weekOf`, or HT's rule from
 * [today] when the file has none), then each whole-slot edit. On a v2 schedule a weekday-key edit is
 * shown on its dated slot (see [resolvePendingSlotKey]).
 */
fun overlayPendingDeliveryRequest(
    schedule: DeliverySchedule,
    request: DeliveryScheduleEditRequest?,
    today: LocalDate = shopToday()
): DeliverySchedule {
    if (request == null) return schedule
    var result = schedule
    if (request.resetAll) {
        val resetStart = schedule.weekOf ?: mirrorWeekOf(today)
        val resetEnd = resetStart.plusDays(4)
        result = result.copy(
            slots = result.slots.mapValues { DeliverySlot() },
            datedSlots = result.datedSlots.filterKeys { key ->
                val date = parseDatedSlotKey(key)?.date ?: return@filterKeys true
                date < resetStart || date > resetEnd
            }
        )
    }
    request.slotEdits.forEach { edit ->
        val slot = resolvePendingSlotKey(edit.slot, schedule, today) ?: return@forEach
        // HT dates a job in one place: a dated edit moves its jobs out of every other slot (and the
        // weekday mirror). Without this a queued move shows twice, and a later offline edit of the
        // source slot would resend the moved job and re-date it back.
        if (result.isDated && parseDatedSlotKey(slot) != null) {
            val moved = edit.jobs.map { it.folderName.lowercase() }.filter { it.isNotBlank() }.toSet()
            if (moved.isNotEmpty()) {
                fun strip(slot: DeliverySlot) =
                    if (slot.jobs.any { it.folderName.lowercase() in moved }) {
                        DeliverySlot(slot.jobs.filterNot { it.folderName.lowercase() in moved })
                    } else slot
                result = result.copy(
                    slots = result.slots.mapValues { strip(it.value) },
                    datedSlots = result.datedSlots.mapValues { (key, existing) -> if (key == slot) existing else strip(existing) }
                )
            }
        }
        result = result.withSlot(slot, edit.jobs)
    }
    return result
}

/** The raw slot keys of [request] (v1 behavior; weekday keys stay weekday keys). */
fun pendingDeliverySlotKeys(request: DeliveryScheduleEditRequest?): Set<String> =
    request?.slotEdits?.map { it.slot }?.toSet().orEmpty()

/** Slot keys of [request] as the UI shows them: on a v2 [schedule] weekday keys map onto dated keys. */
fun pendingDeliverySlotKeys(
    request: DeliveryScheduleEditRequest?,
    schedule: DeliverySchedule,
    today: LocalDate = shopToday()
): Set<String> =
    request?.slotEdits?.map { resolvePendingSlotKey(it.slot, schedule, today) ?: it.slot }?.toSet().orEmpty()
