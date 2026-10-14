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
 * Jobs in the whole day of [slotKey] (its AM plus PM slots). A dated key counts that date's two
 * slots, a weekday key counts that weekday's two slots, any other key just its own slot.
 */
fun deliveryDayCount(schedule: DeliverySchedule, slotKey: String): Int =
    deliveryDayJobs(schedule, slotKey).size

/** Every job in the AM and PM slots of [slotKey]'s day (see [deliveryDayCount] for the key rules). */
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
 * One day's view, shared by [buildDeliveryWeek] and [weekendTodayDay]. A dated schedule keys the
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

/**
 * On Saturday/Sunday HT's `weekOf` is the coming Monday, so a delivery dated today sits in the week
 * BEFORE `weekOf` and the week view never shows it. Returns today's day when the schedule is dated,
 * today is a weekend day and it has at least one job; otherwise null.
 *
 * [weekStart] is the Monday of the week the banner displays. If today is not before it (HT has not
 * rewritten `weekOf` yet, so the displayed Mon-Sun week already holds today), the week view shows
 * today itself and a second copy here would duplicate the day.
 */
fun weekendTodayDay(schedule: DeliverySchedule, today: LocalDate, weekStart: LocalDate): DeliveryDayView? {
    if (!schedule.isDated) return null
    if (today >= weekStart) return null
    if (today.dayOfWeek != DayOfWeek.SATURDAY && today.dayOfWeek != DayOfWeek.SUNDAY) return null
    return buildDeliveryDay(schedule, today, null, today).takeIf { it.count > 0 }
}

fun buildDeliveryWeek(
    schedule: DeliverySchedule,
    weekStart: LocalDate,
    today: LocalDate,
    includeEmptyWeekend: Boolean
): DeliveryWeekView {
    val days = (0L until 7L).mapNotNull { offset ->
        val date = weekStart.plusDays(offset)
        val weekend = offset >= 5
        if (!schedule.isDated && weekend) return@mapNotNull null
        val day = buildDeliveryDay(schedule, date, DELIVERY_DAYS.getOrNull(offset.toInt()), today)
        if (weekend && !includeEmptyWeekend && day.count == 0) return@mapNotNull null
        day
    }
    return DeliveryWeekView(
        weekStart = weekStart,
        days = days,
        isDefaultWeek = weekStart == defaultDeliveryWeekStart(schedule, today),
        canGoBack = schedule.isDated && weekStart.minusDays(1) >= today.minusDays(DELIVERY_HISTORY_DAYS),
        canGoForward = schedule.isDated
    )
}

/**
 * Shows this tablet's own queued (not yet consumed) request on top of the published schedule.
 * HT applies `resetAll` first (clears the mirrored Mon-Fri week only), then each whole-slot edit.
 */
fun overlayPendingDeliveryRequest(
    schedule: DeliverySchedule,
    request: DeliveryScheduleEditRequest?
): DeliverySchedule {
    if (request == null) return schedule
    var result = schedule
    if (request.resetAll) {
        val weekOf = schedule.weekOf
        result = result.copy(
            slots = result.slots.mapValues { DeliverySlot() },
            datedSlots = if (weekOf == null) emptyMap() else result.datedSlots.filterKeys { key ->
                val date = parseDatedSlotKey(key)?.date ?: return@filterKeys true
                date < weekOf || date > weekOf.plusDays(4)
            }
        )
    }
    request.slotEdits.forEach { edit ->
        if (parseDatedSlotKey(edit.slot) != null || isWeekdaySlotKey(edit.slot)) {
            // HT dates a job in one place: a dated edit moves its jobs out of every other slot (and the
            // weekday mirror). Without this a queued move shows twice, and a later offline edit of the
            // source slot would resend the moved job and re-date it back.
            if (result.isDated && parseDatedSlotKey(edit.slot) != null) {
                val moved = edit.jobs.map { it.folderName.lowercase() }.filter { it.isNotBlank() }.toSet()
                if (moved.isNotEmpty()) {
                    fun strip(slot: DeliverySlot) =
                        if (slot.jobs.any { it.folderName.lowercase() in moved }) {
                            DeliverySlot(slot.jobs.filterNot { it.folderName.lowercase() in moved })
                        } else slot
                    result = result.copy(
                        slots = result.slots.mapValues { strip(it.value) },
                        datedSlots = result.datedSlots.mapValues { (key, slot) -> if (key == edit.slot) slot else strip(slot) }
                    )
                }
            }
            result = result.withSlot(edit.slot, edit.jobs)
        }
    }
    return result
}

fun pendingDeliverySlotKeys(request: DeliveryScheduleEditRequest?): Set<String> =
    request?.slotEdits?.map { it.slot }?.toSet().orEmpty()
