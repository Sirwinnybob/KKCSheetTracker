package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.StatusCounts

internal fun isRecentInProgressMaterial(counts: StatusCounts): Boolean {
    // "Recent in-progress" means work has actually started (>=1 complete sheet),
    // and the material is not fully done yet.
    return counts.total > 0 &&
        counts.complete > 0 &&
        (counts.complete + counts.skipped + counts.reNested) < counts.total
}
