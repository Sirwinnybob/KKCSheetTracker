package com.kkc.sheettracker.data

internal fun nextBackoffDelayMs(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)
