package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocSummary
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodStatusCounts

/**
 * Pure rules for cutlist docs that are PDF-derived (legacy) or metadata-backed
 * (published from Cabinet Vision exports), one or several per document type.
 */
object HardwoodDocuments {
    const val SOURCE_KIND_CV_METADATA = "CV_METADATA"

    fun isMetadataBacked(doc: HardwoodDocumentIndex): Boolean = doc.sourceKind == SOURCE_KIND_CV_METADATA

    /** Door lists are retired; otherwise a doc opens if it is metadata-backed or its PDF exists. */
    fun isAvailable(doc: HardwoodDocumentIndex, pdfExists: (String) -> Boolean): Boolean {
        if (doc.docType == HardwoodDocType.DOOR_LIST) return false
        if (isMetadataBacked(doc)) return true
        return doc.pdfFilename.isNotBlank() && pdfExists(doc.pdfFilename)
    }

    /**
     * Gson turns an unknown enum string into null even in a non-null Kotlin field;
     * a doc type this build does not know must be dropped, not mislabelled.
     */
    @Suppress("SENSELESS_COMPARISON", "USELESS_CAST")
    fun dropUnknownDocTypes(index: HardwoodCutlistIndex?): HardwoodCutlistIndex? {
        if (index == null) return null
        val documents = (index.documents as List<HardwoodDocumentIndex>?).orEmpty()
        val known = documents.filter { (it.docType as HardwoodDocType?) != null }
        return if (documents === index.documents && known.size == documents.size) index else index.copy(documents = known)
    }

    fun docsOfType(index: HardwoodCutlistIndex?, type: HardwoodDocType): List<HardwoodDocumentIndex> =
        index?.documents.orEmpty().filter { it.docType == type }

    /** Rows of every doc of [type] (all batches), or null when the index has no doc of that type. */
    fun rowsOfTypeOrNull(index: HardwoodCutlistIndex?, type: HardwoodDocType): List<HardwoodCutlistRow>? {
        val docs = docsOfType(index, type)
        return if (docs.isEmpty()) null else docs.flatMap { it.rows.orEmpty() }
    }

    /** One doc per type for per-type tiles: single docs pass through; batches merge into one doc. */
    fun mergedByType(docs: List<HardwoodDocumentIndex>): Map<HardwoodDocType, HardwoodDocumentIndex> =
        docs.groupBy { it.docType }.mapValues { (_, group) ->
            if (group.size == 1) {
                group.first()
            } else {
                group.first().copy(
                    rows = group.flatMap { it.rows.orEmpty() },
                    totals = group.flatMap { it.totals.orEmpty() },
                    doors = group.flatMap { it.doors.orEmpty() },
                    batchId = "",
                    batchName = "",
                    revisionId = ""
                )
            }
        }

    fun mergeSummaries(summaries: List<HardwoodDocSummary>): Map<HardwoodDocType, HardwoodDocSummary> =
        summaries.groupBy { it.docType }.mapValues { (_, group) ->
            if (group.size == 1) {
                group.first()
            } else {
                group.first().copy(
                    rowCount = group.sumOf { it.rowCount },
                    counts = HardwoodStatusCounts(
                        totalPieces = group.sumOf { it.counts.totalPieces },
                        donePieces = group.sumOf { it.counts.donePieces },
                        badPieces = group.sumOf { it.counts.badPieces },
                        skippedPieces = group.sumOf { it.counts.skippedPieces }
                    )
                )
            }
        }

    /** The doc of [type] for an explicit batch, else the one owning [owningRowId], else the first. */
    fun selectDoc(
        docs: List<HardwoodDocumentIndex>,
        type: HardwoodDocType,
        batchId: String?,
        owningRowId: String?
    ): HardwoodDocumentIndex? {
        val ofType = docs.filter { it.docType == type }
        if (ofType.isEmpty()) return null
        if (batchId != null) ofType.firstOrNull { it.batchId == batchId }?.let { return it }
        if (owningRowId != null) {
            ofType.firstOrNull { doc -> doc.rows.any { it.rowId == owningRowId } }?.let { return it }
        }
        return ofType.first()
    }
}
