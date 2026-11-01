package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.StatusCounts

data class ViewerMixSelection(
    val name: String,
    val mixFile: String
)

fun shouldShowPendingBadPartAction(
    mixSelection: ViewerMixSelection?,
    pendingCount: Int
): Boolean = mixSelection == null && pendingCount > 0

fun statusCountsForPages(
    pages: List<Int>,
    statusByPage: Map<Int, SheetStatus>
): StatusCounts {
    var complete = 0
    var bad = 0
    var skipped = 0
    var notStarted = 0
    var reNested = 0
    pages.distinct().forEach { page ->
        when (statusByPage[page] ?: SheetStatus.NOT_STARTED) {
            SheetStatus.HAS_BAD_PARTS -> {
                complete += 1
                bad += 1
            }
            SheetStatus.COMPLETE -> complete += 1
            SheetStatus.RE_NESTED -> reNested += 1
            SheetStatus.SKIPPED -> skipped += 1
            else -> notStarted += 1
        }
    }
    return StatusCounts(
        total = pages.distinct().size,
        complete = complete,
        bad = bad,
        skipped = skipped,
        notStarted = notStarted,
        reNested = reNested
    )
}
