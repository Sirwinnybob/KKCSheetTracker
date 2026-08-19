package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.CacheIndexRoot

internal fun nextBackoffDelayMs(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

internal data class LiveIndexDeltaWire(
    val type: String? = null,
    val folderName: String? = null,
    val revision: Long? = null,
    val index: CacheIndexRoot? = null
)

internal data class LiveIndexEnvelope(
    val type: String? = null,
    val serverInstanceId: String? = null,
    val revision: Long? = null,
    val jobs: Map<String, CacheIndexRoot>? = null,
    val delta: LiveIndexDeltaWire? = null,
    val message: String? = null
)
