package com.kkc.sheettracker.data

import kotlin.math.ceil

/**
 * Mirrors the timeclock hub's billing rules (`_calc_duration_hours` in timeclock-hub/app.py):
 * duration rounds UP to the next 15-minute increment, and punches strictly under 7 minutes
 * are discarded as accidental.
 */
object ClockInBilling {
    const val MIN_COUNTED_MS = 7L * 60_000L
    private const val INCREMENT_MS = 15L * 60_000L

    /** Hours the hub would bill for [elapsedMs]; 0.0 while under the 7-minute threshold. */
    fun billedHours(elapsedMs: Long): Double {
        if (elapsedMs < MIN_COUNTED_MS) return 0.0
        val minutes = elapsedMs / 60_000.0
        return ceil(minutes / 15.0) * 15.0 / 60.0
    }

    /** Clock-out dialog prefill: billed hours, never below one increment so the entry is editable. */
    fun clockOutPrefillHours(elapsedMs: Long): Double = billedHours(elapsedMs).coerceAtLeast(0.25)

    /** Milliseconds until [billedHours] next changes (counting threshold, then each 15-min boundary). */
    fun msUntilNextChange(elapsedMs: Long): Long {
        val safe = elapsedMs.coerceAtLeast(0L)
        if (safe < MIN_COUNTED_MS) return MIN_COUNTED_MS - safe
        // ceil() bills an exact boundary at that increment, so the next step lands just past it.
        val boundary = ((safe + INCREMENT_MS - 1L) / INCREMENT_MS) * INCREMENT_MS
        return boundary + 1L - safe
    }

    fun formatHours(hours: Double): String = "%.2f hr".format(hours)

    /** Live elapsed as H:MM:SS (no zero-padded hour). */
    fun formatElapsed(elapsedMs: Long): String {
        val total = (elapsedMs / 1000L).coerceAtLeast(0L)
        return "%d:%02d:%02d".format(total / 3600L, (total % 3600L) / 60L, total % 60L)
    }

    /** Short "in N min" countdown, rounded up so it never reads "in 0 min". */
    fun formatCountdown(ms: Long): String {
        val minutes = ceil(ms.coerceAtLeast(0L) / 60_000.0).toLong().coerceAtLeast(1L)
        return "$minutes min"
    }
}
