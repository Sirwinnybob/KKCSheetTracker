package com.kkc.sheettracker.data.models

data class SpecialtyJob(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val hiddenFromProduction: Boolean = false,
    val totalItems: Int = 0,
    val completedItems: Int = 0,
    val remainingItems: Int = 0,
    val completionFraction: Float = 0f,
    val resolvedItems: List<SpecialtyResolvedItem> = emptyList(),
    val lineupPosition: Int? = null,
    val labels: List<JobLabel> = emptyList(),
    val isPending: Boolean = false,
    val boardSection: Int = 0
)

data class SpecialtyScanSnapshot(
    val generation: Long = 0L,
    val basePath: String = "",
    val jobs: List<SpecialtyJob> = emptyList(),
    val startedAt: Long = 0L,
    val completedAt: Long = 0L
)

data class SpecialtyScanState(
    val status: ScanStatus = ScanStatus.IDLE,
    val snapshot: SpecialtyScanSnapshot = SpecialtyScanSnapshot(),
    val errorMessage: String? = null,
    val lastRefreshReason: RefreshReason? = null
)

data class StationProgress(
    val station: String,
    val completed: Int,
    val total: Int
)

data class SpecialtyJobCard(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val hiddenFromProduction: Boolean = false,
    val totalItems: Int = 0,
    val completedItems: Int = 0,
    val remainingItems: Int = 0,
    val completionFraction: Float = 0f,
    val stationProgress: List<StationProgress> = emptyList(),
    val lineupPosition: Int? = null,
    val labels: List<JobLabel> = emptyList(),
    val isPending: Boolean = false,
    val boardSection: Int = 0
)
