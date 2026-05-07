package com.kkc.sheettracker.data.models

data class Job(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val materials: List<Material> = emptyList()
) {
    val totalSheets: Int get() = materials.sumOf { it.pageCount }
}

data class Material(
    val pdfFilename: String,
    val materialName: String,
    val pageCount: Int,
    val fileFingerprint: String = "",
    val metadata: MaterialMetadata? = null
)

data class MaterialMetadata(
    val jobNumber: String = "",
    val jobName: String = "",
    val material: String = "",
    val pdfFilename: String = "",
    val remakeLabel: String? = null,
    val pages: List<PageMetadata> = emptyList()
)

data class RemadePartMetadata(
    val partNumber: Int = 0,
    val partName: String = "",
    val sourcePdfFilename: String? = null,
    val sourcePage: Int? = null,
    val sourcePartNumber: Int? = null
)

data class RemakePageMetadata(
    val label: String = "",
    val remadeParts: List<RemadePartMetadata> = emptyList()
)

data class PageMetadata(
    val pageNumber: Int = 0,
    val sheetId: String = "",
    val sheetFiles: List<String> = emptyList(),
    val sheetDimensions: List<Double>? = null,
    val logicalSheetKey: String? = null,
    val isPartListContinuation: Boolean = false,
    val continuationHeadPage: Int? = null,
    val trackingExcluded: Boolean = false,
    val hiddenInApp: Boolean = false,
    val partListSpansPages: List<Int>? = null,
    val thumbnailPath: String? = null,
    val parts: List<Part> = emptyList(),
    val ocrBoxes: Map<String, List<OcrBoxMetadata>>? = null,
    val ocrSource: String? = null,
    val ocrGeneratedAt: String? = null,
    val ocrVersion: String? = null,
    val remake: RemakePageMetadata? = null
)

data class OcrBoxMetadata(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

data class Part(
    val number: Int = 0,
    val width: Double = 0.0,
    val length: Double = 0.0,
    val name: String = "",
    val cabNumber: Int = 0,
    val room: String = ""
)

enum class ReferenceDocType {
    ASSEMBLY,
    PLANS_ELEVATIONS
}

data class AssemblySheetPart(
    val qty: Int = 0,
    val width: Double = 0.0,
    val length: Double = 0.0,
    val description: String = "",
    val material: String = "",
    val sectionType: String = "",
    val isPurchased: Boolean = false
)

data class CabinetPageDetail(
    val cabinets: List<String> = emptyList(),
    val room: String? = null,
    val wall: String? = null,
    val parts: List<AssemblySheetPart> = emptyList()
)

data class ReferenceDocumentIndex(
    val pdfFilename: String = "",
    val cabinetToPages: Map<String, List<Int>> = emptyMap(),
    val pageDetails: Map<String, CabinetPageDetail> = emptyMap()
)

data class CabinetSheetIndexDocuments(
    val assembly: ReferenceDocumentIndex = ReferenceDocumentIndex(),
    val plansElevations: ReferenceDocumentIndex = ReferenceDocumentIndex()
)

data class CabinetSheetIndex(
    val documents: CabinetSheetIndexDocuments = CabinetSheetIndexDocuments(),
    val generatedAt: String? = null
)

enum class HardwoodDocType {
    FACE_FRAME_CUT_LIST,
    NAILER_CUT_LIST,
    DOOR_CUT_LIST,
    DOOR_LIST
}

data class HardwoodCutlistRow(
    val rowId: String = "",
    val page: Int = 1,
    val rowOrdinal: Int = 0,
    val qty: Int = 0,
    val material: String? = null,
    val description: String = "",
    val width: String = "",
    val length: String = "",
    val cabinets: List<String> = emptyList(),
    val rawCabinetText: String = ""
)

data class HardwoodTotalsBlock(
    val page: Int = 1,
    val sourcePages: List<Int> = emptyList(),
    val material: String? = null,
    val widthValues: List<String> = emptyList(),
    val lengthValues: List<String> = emptyList(),
    val ripsValues: List<String> = emptyList()
)

data class HardwoodDocumentIndex(
    val docType: HardwoodDocType = HardwoodDocType.FACE_FRAME_CUT_LIST,
    val pdfFilename: String = "",
    val pageCount: Int = 0,
    val rows: List<HardwoodCutlistRow> = emptyList(),
    val totals: List<HardwoodTotalsBlock> = emptyList()
)

data class HardwoodCutlistIndex(
    val generatedAt: String? = null,
    val documents: List<HardwoodDocumentIndex> = emptyList()
)

data class HardwoodJob(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val index: HardwoodCutlistIndex? = null
)

data class HardwoodSearchEntry(
    val jobFolderName: String,
    val jobNumber: String,
    val jobName: String,
    val docType: HardwoodDocType,
    val pdfFilename: String,
    val rowId: String,
    val description: String,
    val width: String,
    val length: String,
    val cabinetNumbers: List<String> = emptyList(),
    val rawCabinetText: String = ""
)

data class HardwoodTrackerAction(
    val docType: String = "",
    val rowId: String = "",
    val totalsKey: String? = null,
    val action: String = "",
    val value: Int? = null,
    val timestamp: String = ""
)

data class HardwoodTotalsTallyKey(
    val docType: String = "",
    val blockIndex: Int = 0,
    val lineIndex: Int = 0
) {
    val stableKey: String
        get() = "$docType|$blockIndex|$lineIndex"
}

object HardwoodTrackerActions {
    const val SET_DONE_COUNT = "set_done_count"
    const val SET_BAD_COUNT = "set_bad_count"
    const val SET_SKIPPED = "set_skipped"
    const val CLEAR_SKIPPED = "clear_skipped"
    const val ADD_TOTALS_RIP10_DONE_COUNT = "add_totals_rip10_done_count"
    const val SET_TOTALS_RIP10_DONE_COUNT = "set_totals_rip10_done_count"
}

data class HardwoodTabletProgress(
    val tabletId: String,
    val actions: List<HardwoodTrackerAction> = emptyList()
)

data class HardwoodRowProgress(
    val doneCount: Int = 0,
    val badCount: Int = 0,
    val skipped: Boolean = false
)

data class HardwoodStatusCounts(
    val totalPieces: Int = 0,
    val donePieces: Int = 0,
    val badPieces: Int = 0,
    val skippedPieces: Int = 0
) {
    val remainingPieces: Int
        get() = (totalPieces - donePieces - skippedPieces).coerceAtLeast(0)
    val completionFraction: Float
        get() = if (totalPieces <= 0) 0f else donePieces.toFloat() / totalPieces.toFloat()
}

data class HardwoodDocSummary(
    val docType: HardwoodDocType,
    val pdfFilename: String = "",
    val rowCount: Int = 0,
    val counts: HardwoodStatusCounts = HardwoodStatusCounts()
)

data class HardwoodJobSummary(
    val job: HardwoodJob,
    val counts: HardwoodStatusCounts = HardwoodStatusCounts(),
    val documents: List<HardwoodDocSummary> = emptyList()
)

data class HardwoodDashboardModel(
    val totalJobs: Int = 0,
    val totalPieces: Int = 0,
    val donePieces: Int = 0,
    val badPieces: Int = 0,
    val skippedPieces: Int = 0
)

data class HardwoodScanSnapshot(
    val generation: Long = 0L,
    val basePath: String = "",
    val jobs: List<HardwoodJob> = emptyList(),
    val searchIndex: List<HardwoodSearchEntry> = emptyList(),
    val startedAt: Long = 0L,
    val completedAt: Long = 0L
)

data class HardwoodScanState(
    val status: ScanStatus = ScanStatus.IDLE,
    val snapshot: HardwoodScanSnapshot = HardwoodScanSnapshot(),
    val errorMessage: String? = null,
    val lastRefreshReason: RefreshReason? = null
)

enum class SheetStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETE,
    SKIPPED,
    HAS_BAD_PARTS
}

data class TrackerAction(
    val file: String,
    val page: Int,
    val part: Int? = null,
    val action: String,
    val timestamp: String,
    val fileFingerprint: String? = null
)

data class TabletProgress(
    val tabletId: String,
    val actions: List<TrackerAction> = emptyList()
)

data class StatusCounts(
    val total: Int = 0,
    val complete: Int = 0,
    val bad: Int = 0,
    val skipped: Int = 0,
    val notStarted: Int = 0
)

data class PartSearchEntry(
    val jobFolderName: String,
    val jobNumber: String,
    val materialName: String,
    val pdfFilename: String,
    val pageNumber: Int,
    val partNumber: Int,
    val partName: String,
    val room: String,
    val cabNumber: Int
)

enum class ScanStatus {
    IDLE,
    LOADING,
    READY,
    ERROR
}

enum class ScanIssueType {
    MISSING_METADATA,
    INVALID_METADATA_JSON,
    PDF_READ_ERROR,
    PAGE_COUNT_ERROR
}

data class ScanIssue(
    val type: ScanIssueType,
    val jobFolderName: String? = null,
    val materialName: String? = null,
    val pdfFilename: String? = null,
    val detail: String? = null
)

enum class RefreshReason {
    APP_START,
    APP_FOREGROUND,
    USER_REFRESH,
    BASE_PATH_CHANGED,
    VIEWER_RECOVERY
}

data class ScanSnapshot(
    val generation: Long = 0L,
    val basePath: String = "",
    val jobs: List<Job> = emptyList(),
    val searchIndex: List<PartSearchEntry> = emptyList(),
    val issues: List<ScanIssue> = emptyList(),
    val startedAt: Long = 0L,
    val completedAt: Long = 0L
)

data class ScanSnapshotState(
    val status: ScanStatus = ScanStatus.IDLE,
    val snapshot: ScanSnapshot = ScanSnapshot(),
    val errorMessage: String? = null,
    val lastRefreshReason: RefreshReason? = null
)

enum class AppDerivationStatus {
    IDLE,
    DERIVING,
    READY,
    ERROR
}

data class AppUiState(
    val status: AppDerivationStatus = AppDerivationStatus.IDLE,
    val isRefreshing: Boolean = false,
    val scanGeneration: Long = 0L,
    val progressVersion: Long = 0L,
    val lastUpdatedAt: Long = 0L,
    val scanIssues: List<ScanIssue> = emptyList(),
    val errorMessage: String? = null
)

data class JobMaterialKey(
    val jobFolderName: String,
    val pdfFilename: String
)

data class SheetStatusKey(
    val jobFolderName: String,
    val pdfFilename: String,
    val page: Int,
    val fileFingerprint: String
)

data class SheetStatusSnapshot(
    val status: SheetStatus = SheetStatus.NOT_STARTED,
    val committedBadCount: Int = 0,
    val hasDraftBadParts: Boolean = false
)

data class MaterialUiModel(
    val pdfFilename: String,
    val materialName: String,
    val counts: StatusCounts = StatusCounts(),
    val completionFraction: Float = 0f,
    val pageStatuses: List<SheetStatusSnapshot> = emptyList()
)

data class JobUiModel(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val counts: StatusCounts = StatusCounts(),
    val completionFraction: Float = 0f,
    val materials: List<MaterialUiModel> = emptyList()
)

data class DashboardFlaggedSheetItem(
    val jobFolderName: String,
    val materialName: String,
    val pdfFilename: String,
    val fileFingerprint: String,
    val sheetPage: Int,
    val committedBadCount: Int = 0,
    val hasDraftBadParts: Boolean = false
)

data class DashboardRecentMaterialItem(
    val jobFolderName: String,
    val jobNumber: String,
    val materialName: String,
    val pdfFilename: String,
    val fileFingerprint: String,
    val lastTouchedPage: Int,
    val lastTouchedAtMs: Long,
    val counts: StatusCounts = StatusCounts(),
    val completionFraction: Float = 0f,
    val thumbnailPath: String? = null
)

data class DashboardUiModel(
    val totalJobs: Int = 0,
    val totalSheets: Int = 0,
    val completedSheets: Int = 0,
    val badPartsSheets: Int = 0,
    val skippedSheets: Int = 0,
    val badItems: List<DashboardFlaggedSheetItem> = emptyList(),
    val skippedItems: List<DashboardFlaggedSheetItem> = emptyList(),
    val recentInProgressMaterials: List<DashboardRecentMaterialItem> = emptyList()
)

// ── Assembly Mode ─────────────────────────────────────────────────────────────

data class AssemblyCncSummary(
    val totalSheets: Int = 0,
    val completedSheets: Int = 0,
    val skippedSheets: Int = 0,
    val badPartsSheets: Int = 0
) {
    val completionFraction: Float
        get() = if (totalSheets <= 0) 0f else completedSheets.toFloat() / totalSheets.toFloat()
}

data class AssemblyHardwoodsSummary(
    val totalPieces: Int = 0,
    val donePieces: Int = 0,
    val badPieces: Int = 0,
    val skippedPieces: Int = 0
) {
    val completionFraction: Float
        get() = if (totalPieces <= 0) 0f else donePieces.toFloat() / totalPieces.toFloat()
}

data class AssemblyJob(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val cabinetSheetIndex: CabinetSheetIndex? = null,
    val cncSummary: AssemblyCncSummary? = null,
    val hardwoodsSummary: AssemblyHardwoodsSummary? = null
)

data class AssemblyJobCard(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val cncSummary: AssemblyCncSummary,
    val hardwoodsSummary: AssemblyHardwoodsSummary,
    val hasBothModes: Boolean
)

data class AssemblyCncPart(
    val materialName: String,
    val pdfFilename: String,
    val pageNumber: Int,
    val partNumber: Int,
    val partName: String,
    val width: Double,
    val length: Double,
    val room: String,
    val sheetStatus: SheetStatus,
    val isBadPart: Boolean
)

data class AssemblyHardwoodRow(
    val docType: HardwoodDocType,
    val description: String,
    val material: String?,
    val qty: Int,
    val width: String,
    val length: String,
    val doneCount: Int,
    val badCount: Int,
    val skipped: Boolean
)

data class AssemblyBomEntry(
    val part: AssemblySheetPart,
    val cncPart: AssemblyCncPart? = null,
    val hardwoodRow: AssemblyHardwoodRow? = null
) {
    val isTracked: Boolean get() = cncPart != null || hardwoodRow != null
}

data class AssemblyCabinetParts(
    val cabinetNumber: String,
    val bom: List<AssemblyBomEntry> = emptyList(),
    val cncParts: List<AssemblyCncPart> = emptyList(),
    val hardwoodRows: List<AssemblyHardwoodRow> = emptyList()
)

data class AssemblyScanSnapshot(
    val generation: Long = 0L,
    val basePath: String = "",
    val jobs: List<AssemblyJob> = emptyList(),
    val startedAt: Long = 0L,
    val completedAt: Long = 0L
)

data class AssemblyScanState(
    val status: ScanStatus = ScanStatus.IDLE,
    val snapshot: AssemblyScanSnapshot = AssemblyScanSnapshot(),
    val errorMessage: String? = null
)
