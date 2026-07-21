package com.kkc.sheettracker.ui.jobs

import com.kkc.sheettracker.data.models.JobLabel
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.MaterialSegmentData
import kotlinx.coroutines.flow.StateFlow

sealed interface ProgressStyle {
    data class Cnc(
        val counts: StatusCounts,
        val fraction: Float,
        val materialSegments: List<MaterialSegmentData> = emptyList()
    ) : ProgressStyle

    data class Hardwoods(
        val counts: com.kkc.sheettracker.data.models.HardwoodStatusCounts,
        val fraction: Float,
        val docCount: Int = 0,
        val docSegments: List<MaterialSegmentData> = emptyList()
    ) : ProgressStyle

    data class Assembly(
        val cncCounts: StatusCounts,
        val hardwoodCounts: com.kkc.sheettracker.data.models.HardwoodStatusCounts,
        val bothModes: Boolean
    ) : ProgressStyle

    data class Specialty(
        val stationProgress: List<com.kkc.sheettracker.data.models.StationProgress>,
        val totalItems: Int,
        val completedItems: Int,
        val fraction: Float
    ) : ProgressStyle
}

enum class JobBadge {
    PENDING_DELIVERY,
    HAS_DELIVERY_SHEET,
    HAS_3D_ASSETS,
    HAS_HISTORY,
    HIDDEN_IN_PRODUCTION
}

data class UnifiedJobUiModel(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val isPinned: Boolean,
    val isPending: Boolean,
    val boardSection: Int,
    val lineupPosition: Int?,
    val progressStyle: ProgressStyle,
    val badges: Set<JobBadge> = emptySet(),
    val labels: List<JobLabel> = emptyList(),
    val historyCount: Int? = null,
    val onCardClick: () -> Unit = {},
    val onView3DClick: (() -> Unit)? = null,
    val onViewCoverSheetClick: (() -> Unit)? = null,
    val onHistoryClick: ((String) -> Unit)? = null
)

interface UnifiedJobsSpec {
    val modeName: String
    val scanStatus: StateFlow<ScanStatus>
    val scanGeneration: StateFlow<Long>
    val progressVersion: StateFlow<Long>
    
    fun deriveJobCards(): List<UnifiedJobUiModel>
    fun refresh(reason: RefreshReason, force: Boolean)
    suspend fun resolveBadges(folderName: String): Set<JobBadge> = emptySet()
}

data class JobBrowserItemUiState(
    val job: com.kkc.sheettracker.data.models.Job,
    val counts: StatusCounts,
    val completionFraction: Float,
    val materialSegments: List<MaterialSegmentData>,
    val hasDeliverySheet: Boolean? = null,
    val hasThreeDAssets: Boolean? = null,
    val revisionCount: Int? = null
)

data class HardwoodsJobItemUiState(
    val job: com.kkc.sheettracker.data.models.HardwoodJob,
    val counts: com.kkc.sheettracker.data.models.HardwoodStatusCounts,
    val docCount: Int,
    val docSegments: List<MaterialSegmentData>,
    val availableDocTypes: Set<com.kkc.sheettracker.data.models.HardwoodDocType>
)
