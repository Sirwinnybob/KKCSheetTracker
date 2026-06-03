package com.kkc.sheettracker.data.models

data class Job(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val materials: List<Material> = emptyList(),
    val hiddenFromProduction: Boolean = false,
    val lineupPosition: Int? = null
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
    val room: String = "",
    val rotated: Boolean = false
)

enum class ReferenceDocType {
    ASSEMBLY,
    PLANS_ELEVATIONS,
    DELIVERY_SHEETS
}

data class JobPdfRef(
    val pdfFilename: String,
    val label: String = ""
)

data class JobPdfCatalog(
    val deliverySheet: JobPdfRef? = null,
    val managedDocs: List<JobPdfRef> = emptyList(),
    val otherDocs: List<JobPdfRef> = emptyList()
)

enum class AssemblyPaneSource {
    PLANS,
    ASSEMBLY,
    DELIVERY,
    OTHER
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
    val parts: List<AssemblySheetPart> = emptyList(),
    val sourceVariant: String? = null,
    val sourcePdfFilename: String? = null,
    val sourcePage: Int? = null
)

data class AssemblySourceDocumentIndex(
    val variant: String = "",
    val pdfFilename: String = "",
    val cabinetToPages: Map<String, List<Int>> = emptyMap(),
    val pageDetails: Map<String, CabinetPageDetail> = emptyMap()
)

data class AssemblyVirtualEntry(
    val variant: String = "",
    val pdfFilename: String = "",
    val page: Int = 0,
    val virtualPage: Int = 0
)

data class AssemblyVirtualSourceRef(
    val variant: String = "",
    val pdfFilename: String = "",
    val page: Int = 0,
    val cabinet: String? = null
)

data class AssemblyVirtualCombinedIndex(
    val cabinetOrder: List<String> = emptyList(),
    val entriesByCabinet: Map<String, List<AssemblyVirtualEntry>> = emptyMap(),
    val cabinetToPages: Map<String, List<Int>> = emptyMap(),
    val virtualPageToSource: Map<String, AssemblyVirtualSourceRef> = emptyMap(),
    val pageDetails: Map<String, CabinetPageDetail> = emptyMap(),
    val totalVirtualPages: Int = 0
)

data class ReferenceDocumentIndex(
    val pdfFilename: String = "",
    val cabinetToPages: Map<String, List<Int>> = emptyMap(),
    val pageDetails: Map<String, CabinetPageDetail> = emptyMap(),
    val mode: String? = null,
    val modeSource: String? = null,
    val sources: List<AssemblySourceDocumentIndex> = emptyList(),
    val virtualCombined: AssemblyVirtualCombinedIndex? = null
)

data class DeliveryDocumentIndex(
    val pdfFilename: String = "",
    val mode: String? = null,
    val modeSource: String? = null
)

data class CabinetSheetIndexDocuments(
    val assembly: ReferenceDocumentIndex = ReferenceDocumentIndex(),
    val plansElevations: ReferenceDocumentIndex = ReferenceDocumentIndex(),
    val delivery: DeliveryDocumentIndex = DeliveryDocumentIndex()
)

data class CabinetSheetIndex(
    val documents: CabinetSheetIndexDocuments = CabinetSheetIndexDocuments(),
    val generatedAt: String? = null
)

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
    val hardwoodsSummary: AssemblyHardwoodsSummary? = null,
    val hiddenFromProduction: Boolean = false,
    val lineupPosition: Int? = null
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
    val cncParts: List<AssemblyCncPart> = emptyList(),
    val hardwoodRows: List<AssemblyHardwoodRow> = emptyList()
) {
    val isTracked: Boolean
        get() = cncParts.isNotEmpty() || hardwoodRows.isNotEmpty()
}

data class AssemblyCabinetParts(
    val cabinetNumber: String,
    val bom: List<AssemblyBomEntry> = emptyList(),
    val cncParts: List<AssemblyCncPart> = emptyList(),
    val hardwoodRows: List<AssemblyHardwoodRow> = emptyList()
)

data class AssemblyJobCard(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val cncSummary: AssemblyCncSummary = AssemblyCncSummary(),
    val hardwoodsSummary: AssemblyHardwoodsSummary = AssemblyHardwoodsSummary(),
    val hasBothModes: Boolean = false,
    val hiddenFromProduction: Boolean = false,
    val lineupPosition: Int? = null
)

data class AssemblySearchEntry(
    val jobFolderName: String,
    val jobNumber: String,
    val jobName: String,
    val cabinetNumber: String,
    val room: String? = null,
    val wall: String? = null,
    val assemblyPage: Int? = null,
    val plansPage: Int? = null,
    val description: String = "",
    val material: String = "",
    val sectionType: String = ""
)

enum class HardwoodDocType {
    FACE_FRAME_CUT_LIST,
    NAILER_CUT_LIST,
    DOOR_CUT_LIST,
    DOOR_LIST
}

enum class BoardStockSource {
    FRAME,
    NAILER,
    DOOR,
    MANUAL
}

data class BoardStockRow(
    val stableKey: String = "",
    val material: String = "",
    val width: String = "",
    val normalizedWidth: Double = 0.0,
    val source: BoardStockSource = BoardStockSource.MANUAL,
    val sourceLabel: String = source.name,
    val totalFeet: Double = 0.0,
    val neededRips: Int = 0,
    val manualCategory: String? = null,
    val manualSubtype: String? = null,
    val notes: String? = null
)

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

data class HardwoodRevisionHistory(
    val schemaVersion: Int = 1,
    val updatedAt: String? = null,
    val currentRevision: Int = 0,
    val revisions: List<HardwoodRevisionEntry> = emptyList(),
    val currentRowStates: List<HardwoodRowRevisionState> = emptyList()
)

data class HardwoodRevisionEntry(
    val revision: Int = 0,
    val kind: String = "DIFF",
    val timestamp: String? = null,
    val added: List<HardwoodRevisionRowSnapshot> = emptyList(),
    val removed: List<HardwoodRevisionRowSnapshot> = emptyList(),
    val modified: List<HardwoodRevisionModifiedEntry> = emptyList()
)

data class HardwoodRevisionRowSnapshot(
    val docType: String = "",
    val rowId: String = "",
    val page: Int = 0,
    val rowOrdinal: Int = 0,
    val qty: Int = 0,
    val material: String = "",
    val description: String = "",
    val width: String = "",
    val length: String = "",
    val cabinets: List<String> = emptyList()
)

data class HardwoodRevisionModifiedEntry(
    val before: HardwoodRevisionRowSnapshot = HardwoodRevisionRowSnapshot(),
    val after: HardwoodRevisionRowSnapshot = HardwoodRevisionRowSnapshot(),
    val changedFields: List<String> = emptyList()
)

data class HardwoodRowRevisionState(
    val docType: String = "",
    val rowId: String = "",
    val latestRevision: Int = 0,
    val changedPendingRecut: Boolean = false
)

data class HardwoodJob(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val index: HardwoodCutlistIndex? = null,
    val hiddenFromProduction: Boolean = false,
    val lineupPosition: Int? = null
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
    val effectiveTotalPieces: Int
        get() = (totalPieces - skippedPieces).coerceAtLeast(0)
    val remainingPieces: Int
        get() = (effectiveTotalPieces - donePieces).coerceAtLeast(0)
    val completionFraction: Float
        get() = when {
            totalPieces <= 0 -> 0f
            effectiveTotalPieces <= 0 -> 1f
            else -> donePieces.toFloat() / effectiveTotalPieces.toFloat()
        }
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
    VIEWER_RECOVERY,
    WATCHER_CHANGE
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
    val pageStatuses: List<SheetStatusSnapshot> = emptyList(),
    val pendingBadPartCount: Int = 0
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
    val nextIncompletePage: Int,
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

enum class SpecialtyItemCategory {
    CUSTOM,
    TO_ORDER
}

enum class SpecialtyStation {
    CNC,
    HARDWOODS,
    SAW,
    EDGE_BANDER,
    ASSEMBLY,
    SPECIALTY,
    DELIVERY
}

data class SpecialtyItemAttachment(
    val id: String,
    val filename: String,
    val originalName: String,
    val mimeType: String? = null
)

data class SpecialtyItem(
    val id: String = "",
    val name: String = "",
    val cabinetNumbers: List<String> = emptyList(),
    val category: SpecialtyItemCategory = SpecialtyItemCategory.CUSTOM,
    val stations: List<SpecialtyStation> = emptyList(),
    val supplier: String? = null,
    val model: String? = null,
    val orderDate: String? = null,
    val tracking: String? = null,
    val orderUrl: String? = null,
    val notes: String? = null,
    val attachments: List<SpecialtyItemAttachment> = emptyList(),
    val autoDetected: Boolean = false,
    val createdAt: String? = null,
    val createdBy: String? = null,
    val dimensions: String? = null,
    val quantity: Int? = null,
    val material: String? = null
)

data class TabletSpecialtyItem(
    val id: String,                          // raw UUID — NO "tablet:" prefix stored in JSON
    val name: String,
    val category: SpecialtyItemCategory = SpecialtyItemCategory.CUSTOM,
    val cabinetNumbers: List<String> = emptyList(),
    val stations: List<SpecialtyStation> = emptyList(),
    val dimensions: String? = null,
    val quantity: Int? = null,
    val material: String? = null,
    val supplier: String? = null,
    val modelNumber: String? = null,
    val orderDate: String? = null,
    val trackingNumber: String? = null,
    val orderUrl: String? = null,
    val notes: String? = null,
    val createdAt: String = "",
    val createdByDevice: String = ""
)

data class SpecialtyCompletionState(
    val completed: Boolean = false,
    val completedAt: String? = null,
    val completedBy: String? = null
)

data class SpecialtyTrackerProgress(
    val tabletId: String = "",
    val schemaVersion: Int = 2,
    val itemCompletions: Map<String, Map<String, SpecialtyCompletionState>> = emptyMap()
)

data class SpecialtyResolvedItem(
    val item: SpecialtyItem,
    val completionByKey: Map<String, SpecialtyCompletionState> = emptyMap(),
    val isComplete: Boolean = false
)

data class AdminBoardStockItem(
    val id: String,
    val material: String,
    val name: String,
    /** null = admin explicitly marked NONE (not needed); 0 = blank/unfilled (hidden on tablets) */
    val feet: Double?,
    val mode: String = "bd_ft",
    val ripLength: Int = 10,
    val createdAt: String = "",
    val createdBy: String = ""
)
