package com.kkc.sheettracker.data.models

data class CacheIndexMaterialProgress(
    val materialName: String = "",
    val totalSheets: Int = 0,
    val done: Int = 0,
    val bad: Int = 0,
    val skipped: Int = 0,
    val isRemake: Boolean = false
) {
    fun toStatusCounts(): StatusCounts = StatusCounts(
        total = totalSheets,
        complete = done,
        bad = bad,
        skipped = skipped
    )
}

data class CacheIndexCncProgress(
    val totalSheets: Int = 0,
    val done: Int = 0,
    val bad: Int = 0,
    val skipped: Int = 0,
    val materials: List<CacheIndexMaterialProgress> = emptyList()
)

data class CacheIndexHardwoodsDocType(
    val docType: String = "",
    val total: Int = 0,
    val done: Int = 0,
    val bad: Int = 0,
    val skipped: Int = 0
)

data class CacheIndexHardwoodsProgress(
    val totalPieces: Int = 0,
    val donePieces: Int = 0,
    val badPieces: Int = 0,
    val skippedPieces: Int = 0,
    val docTypes: List<CacheIndexHardwoodsDocType> = emptyList()
)

data class CacheIndexProgressSummary(
    val cnc: CacheIndexCncProgress? = null,
    val hardwoods: CacheIndexHardwoodsProgress? = null,
    val hasDeliverySheet: Boolean = false,
    val has3DAssets: Boolean = false
)

data class CacheIndexJobInfo(
    val folderName: String = "",
    val jobNumber: String = "",
    val jobName: String = "",
    val hiddenFromProduction: Boolean = false,
    val lineupPosition: Int? = null
)

data class CacheIndexRoot(
    val jobInfo: CacheIndexJobInfo? = null,
    val progressSummary: CacheIndexProgressSummary? = null
)
