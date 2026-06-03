package com.kkc.sheettracker.data.unified

import com.kkc.sheettracker.data.models.AssemblyCabinetParts
import com.kkc.sheettracker.data.models.AssemblyJob
import com.kkc.sheettracker.data.models.BoardStockRow
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodRevisionHistory
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.JobPdfCatalog
import com.kkc.sheettracker.data.models.PartSearchEntry
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.data.models.ScanIssue
import com.kkc.sheettracker.data.models.SheetStatus

data class UnifiedJobInfo(
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val hiddenFromProduction: Boolean = false,
    val lineupPosition: Int? = null
)

data class UnifiedCncSnapshot(
    val job: Job,
    val searchIndex: List<PartSearchEntry>,
    val issues: List<ScanIssue> = emptyList()
)

data class UnifiedHardwoodsSnapshot(
    val job: HardwoodJob
)

data class UnifiedHardwoodsRevisionHistory(
    val history: HardwoodRevisionHistory?
)

data class UnifiedAssemblySnapshot(
    val job: AssemblyJob
)

data class UnifiedCabinetJump(
    val assemblyPage: Int?,
    val plansPage: Int?
)

data class UnifiedCabinetContext(
    val contextLine: String
)

data class UnifiedPartOverlayLookup(
    val sheetStatus: (jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String) -> SheetStatus,
    val isBadPart: (jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String, partNumber: Int) -> Boolean,
    val rowProgress: (jobFolderName: String, docType: String, rowId: String) -> HardwoodRowProgress
)

data class UnifiedBoardStockOverlayLookup(
    val rowProgressMap: Map<Pair<String, String>, HardwoodRowProgress> = emptyMap()
)

data class UnifiedMetadataSignature(
    val staticSignature: Long,
    val trackerSignature: Long
)

data class UnifiedPdfPageCountResult(
    val pageCount: Int,
    val errorDetail: String? = null
)

data class UnifiedReferenceLookup(
    val pdfFilename: String?
)

data class UnifiedCabinetIndexLookup(
    val index: CabinetSheetIndex?
)

data class UnifiedAssemblyCabinetParts(
    val parts: AssemblyCabinetParts
)

data class UnifiedBoardStockRows(
    val rows: List<BoardStockRow>
)

data class UnifiedPdfCatalog(
    val catalog: JobPdfCatalog
)

data class UnifiedReferencePresence(
    val exists: Boolean
)

data class UnifiedThreeDPresence(
    val exists: Boolean
)

data class UnifiedReferenceQuery(
    val docType: ReferenceDocType
)
