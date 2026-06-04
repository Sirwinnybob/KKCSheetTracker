package com.kkc.sheettracker.data.unified

interface UnifiedMetadataEngine {
    fun updateBasePath(path: String)
    fun invalidateAll()
    fun invalidateJob(jobFolderName: String)

    fun getBoardGridColumns(): Int
    fun listJobs(): List<UnifiedJobInfo>
    fun getCncSnapshot(jobFolderName: String): UnifiedCncSnapshot?
    fun getHardwoodsSnapshot(jobFolderName: String): UnifiedHardwoodsSnapshot?
    fun getHardwoodsRevisionHistory(jobFolderName: String): UnifiedHardwoodsRevisionHistory
    fun getAssemblySnapshot(jobFolderName: String): UnifiedAssemblySnapshot?

    fun getCabinetSheetIndex(jobFolderName: String): UnifiedCabinetIndexLookup
    fun getPdfCatalog(jobFolderName: String): UnifiedPdfCatalog
    fun findReferencePdfFilename(jobFolderName: String, query: UnifiedReferenceQuery): UnifiedReferenceLookup
    fun hasReferenceDocument(jobFolderName: String, query: UnifiedReferenceQuery): UnifiedReferencePresence
    fun hasThreeDAssets(jobFolderName: String): UnifiedThreeDPresence

    fun resolveCabinetJump(jobFolderName: String, cabinetNumber: String): UnifiedCabinetJump
    fun resolveCabinetContext(jobFolderName: String, cabinetNumber: String): UnifiedCabinetContext
    fun resolveCabinetParts(
        jobFolderName: String,
        cabinetNumber: String,
        overlayLookup: UnifiedPartOverlayLookup
    ): UnifiedAssemblyCabinetParts

    fun getBoardStockRows(
        jobFolderName: String,
        includeProgressOverlay: Boolean = true,
        overlayLookup: UnifiedBoardStockOverlayLookup = UnifiedBoardStockOverlayLookup()
    ): UnifiedBoardStockRows

    fun getSignatures(jobFolderName: String): UnifiedMetadataSignature
}
