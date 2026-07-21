package com.kkc.sheettracker.ui.jobs

import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.SpecialtyJobCard

fun JobBrowserItemUiState.toUnifiedModel(
    isPinned: Boolean,
    onCardClick: () -> Unit,
    onView3DClick: (() -> Unit)? = null,
    onViewCoverSheetClick: (() -> Unit)? = null,
    onHistoryClick: ((String) -> Unit)? = null
): UnifiedJobUiModel {
    val badges = mutableSetOf<JobBadge>()
    if (job.isPending) badges.add(JobBadge.PENDING_DELIVERY)
    if (job.hiddenFromProduction) badges.add(JobBadge.HIDDEN_IN_PRODUCTION)
    if (revisionCount != null && revisionCount > 0) badges.add(JobBadge.HAS_HISTORY)

    return UnifiedJobUiModel(
        folderName = job.folderName,
        jobNumber = job.jobNumber,
        jobName = job.jobName,
        isPinned = isPinned,
        isPending = job.isPending,
        boardSection = job.boardSection,
        lineupPosition = job.lineupPosition,
        badges = badges,
        labels = job.labels,
        historyCount = revisionCount,
        progressStyle = ProgressStyle.Cnc(
            counts = counts,
            fraction = completionFraction,
            materialSegments = materialSegments
        ),
        onCardClick = onCardClick,
        onView3DClick = onView3DClick,
        onViewCoverSheetClick = onViewCoverSheetClick,
        onHistoryClick = onHistoryClick
    )
}

fun HardwoodsJobItemUiState.toUnifiedModel(
    isPinned: Boolean,
    onCardClick: () -> Unit,
    onView3DClick: (() -> Unit)? = null,
    onViewCoverSheetClick: (() -> Unit)? = null,
    onHistoryClick: ((String) -> Unit)? = null
): UnifiedJobUiModel {
    val badges = mutableSetOf<JobBadge>()
    if (job.isPending) badges.add(JobBadge.PENDING_DELIVERY)
    if (job.hiddenFromProduction) badges.add(JobBadge.HIDDEN_IN_PRODUCTION)

    return UnifiedJobUiModel(
        folderName = job.folderName,
        jobNumber = job.jobNumber,
        jobName = job.jobName,
        isPinned = isPinned,
        isPending = job.isPending,
        boardSection = job.boardSection,
        lineupPosition = job.lineupPosition,
        badges = badges,
        labels = job.labels,
        progressStyle = ProgressStyle.Hardwoods(
            counts = counts,
            fraction = counts.completionFraction,
            docCount = docCount,
            docSegments = docSegments
        ),
        onCardClick = onCardClick,
        onView3DClick = onView3DClick,
        onViewCoverSheetClick = onViewCoverSheetClick,
        onHistoryClick = onHistoryClick
    )
}

fun com.kkc.sheettracker.data.models.AssemblyJobCard.toUnifiedModel(
    isPinned: Boolean,
    onCardClick: () -> Unit,
    onView3DClick: (() -> Unit)? = null,
    onViewCoverSheetClick: (() -> Unit)? = null
): UnifiedJobUiModel {
    val badges = mutableSetOf<JobBadge>()
    if (isPending) badges.add(JobBadge.PENDING_DELIVERY)
    if (hiddenFromProduction) badges.add(JobBadge.HIDDEN_IN_PRODUCTION)

    return UnifiedJobUiModel(
        folderName = folderName,
        jobNumber = jobNumber,
        jobName = jobName,
        isPinned = isPinned,
        isPending = isPending,
        boardSection = boardSection,
        lineupPosition = lineupPosition,
        badges = badges,
        labels = labels,
        progressStyle = ProgressStyle.Assembly(
            cncCounts = com.kkc.sheettracker.data.models.StatusCounts(total = cncSummary.totalSheets, complete = cncSummary.completedSheets, skipped = cncSummary.skippedSheets),
            hardwoodCounts = com.kkc.sheettracker.data.models.HardwoodStatusCounts(totalPieces = hardwoodsSummary.totalPieces, donePieces = hardwoodsSummary.donePieces, badPieces = hardwoodsSummary.badPieces, skippedPieces = hardwoodsSummary.skippedPieces),
            bothModes = hasBothModes
        ),
        onCardClick = onCardClick,
        onView3DClick = onView3DClick,
        onViewCoverSheetClick = onViewCoverSheetClick
    )
}

fun SpecialtyJobCard.toUnifiedModel(
    isPinned: Boolean,
    onCardClick: () -> Unit,
    onView3DClick: (() -> Unit)? = null,
    onViewCoverSheetClick: (() -> Unit)? = null
): UnifiedJobUiModel {
    val badges = mutableSetOf<JobBadge>()
    if (isPending) badges.add(JobBadge.PENDING_DELIVERY)
    if (hiddenFromProduction) badges.add(JobBadge.HIDDEN_IN_PRODUCTION)

    return UnifiedJobUiModel(
        folderName = folderName,
        jobNumber = jobNumber,
        jobName = jobName,
        isPinned = isPinned,
        isPending = isPending,
        boardSection = boardSection,
        lineupPosition = lineupPosition,
        badges = badges,
        labels = labels,
        progressStyle = ProgressStyle.Specialty(
            stationProgress = stationProgress,
            totalItems = totalItems,
            completedItems = completedItems,
            fraction = completionFraction
        ),
        onCardClick = onCardClick,
        onView3DClick = onView3DClick,
        onViewCoverSheetClick = onViewCoverSheetClick
    )
}
