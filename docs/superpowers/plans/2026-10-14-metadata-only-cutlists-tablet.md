# Metadata-Only Cutlists on the Tablet — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show, track and navigate Hours Tracker's metadata-backed hardwood cutlist docs (no PDFs, several named batches per document type, door assemblies) on the tablet, offline, without breaking legacy PDF jobs or crashing on unknown future data.

**Architecture:** One pure helper object (`HardwoodDocuments`) owns the new rules: availability, unknown-docType filtering, per-type lookup/merge, and batch selection. Model and decode gain optional batch fields; the two PDF-existence gates call the helper; the ~6 "first doc of this type" roll-ups switch to the helper's per-type merge; the workspace tracks a selected batch and renders chips plus a read-only door list.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Gson, JUnit4 (`app/src/test`), Gradle (`gradlew.bat`).

**Spec:** [docs/superpowers/specs/2026-10-14-metadata-only-cutlists-tablet-design.md](../specs/2026-10-14-metadata-only-cutlists-tablet-design.md). Producer side: Hours Tracker `docs/superpowers/specs/2026-10-14-cv-export-worker-ingestion-design.md` (docs carry `sourceKind="CV_METADATA"`, `batchId`, `batchName`, `revisionId`, `doors[]`, rows with `cv:<batchId>:<hash>` row IDs, `pdfFilename`/`pageCount` absent, `totals: []`).

## Global Constraints

- Package root `com.kkc.sheettracker`; source under `app/src/main/java/com/kkc/sheettracker`, tests under `app/src/test/java/com/kkc/sheettracker`.
- Legacy PDF docs (`sourceKind == ""`, `batchId == ""`) must behave identically; existing tests stay unchanged and green.
- No new dependencies; no uninstall (`adb uninstall` is forbidden); nothing is installed on shop tablets by this work.
- Work in an isolated git worktree of KKCSheetTracker `main`; the main tree has unrelated uncommitted edits (including `Models.kt` and `FileBackedUnifiedMetadataEngine.kt`). Stage only the files each task names; never `git add -A`. Commit trailer: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Test command pattern (from the worktree root): `.\gradlew.bat testDebugUnitTest --tests "<fully.qualified.Class>"`.
- Finishing: because the main tree is dirty in files this branch also edits, a fast-forward merge into `main` may be refused; if so keep the branch and let the user decide (do not stash or discard their edits).

## Review Focus

- A doc whose `docType` is unknown to this build (e.g. a future category) must be dropped, never mislabelled as face frame or crash — Task 1.
- Two batches of the same type must never share completion: marking a row in one must not change the other's counts — Task 2.
- A metadata-only job (no PDFs at all) must still appear with its docs available in the workspace and job detail — Task 2 and 3.
- Opening a row that belongs to the second batch (search result, archive, deep link) must land on that batch, not the first — Task 3.
- Legacy JSON with none of the new keys must decode to the same docs it always did — Task 1.
- Closet-rod and door-panel specialty items must count rows from every batch of the type — Task 2.

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `data/HardwoodDocuments.kt` | create | Pure rules: availability, unknown-type filter, per-type lookup/merge, selection |
| `data/models/Models.kt` | modify | `HardwoodDoorAssembly`; optional fields on `HardwoodDocumentIndex` |
| `data/models/CacheIndexModels.kt` | modify | optional `batchId`/`batchName` on `CacheIndexHardwoodsDocType` |
| `data/unified/FileBackedUnifiedMetadataEngine.kt` | modify | `sanitizeHardwoodCutlistIndex` keeps new fields, drops unknown types |
| `data/SpecialtyStateStore.kt` | modify | closet-rod / door-panel rows across batches |
| `ui/components/doorcutlist/DoorPanelCutListData.kt` | modify | door doc merged across batches |
| `ui/specialty/SpecialtyDoorPanelsScreen.kt` | modify | docs merged by type |
| `ui/jobs/UnifiedModeSpecs.kt` | modify | batch label on progress segments |
| `ui/hardwoods/HardwoodsJobDetailScreen.kt` | modify | availability gate + merged per-type maps |
| `ui/hardwoods/HardwoodsWorkspaceScreen.kt` | modify | availability gate, selected batch, chips, doors panel |
| `ui/hardwoods/HardwoodsBatchUi.kt` | create | `hardwoodsBatchLabel`, `hardwoodsDoorSummaryLine`, chips and doors composables |
| `app/src/test/.../data/HardwoodDocumentsTest.kt` | create | helper rules |
| `app/src/test/.../data/unified/CvCutlistIndexDecodeTest.kt` | create | decode through the real engine |
| `app/src/test/.../data/MetadataBatchRollupTest.kt` | create | roll-ups and progress isolation |
| `app/src/test/.../ui/hardwoods/HardwoodsBatchUiTest.kt` | create | pure label/summary functions |

All source paths are relative to `app/src/main/java/com/kkc/sheettracker`.

---

### Task 1: Model, decode hardening and the `HardwoodDocuments` helper

**Files:**
- Create: `data/HardwoodDocuments.kt`, `app/src/test/java/com/kkc/sheettracker/data/HardwoodDocumentsTest.kt`, `app/src/test/java/com/kkc/sheettracker/data/unified/CvCutlistIndexDecodeTest.kt`
- Modify: `data/models/Models.kt` (class at ~362), `data/models/CacheIndexModels.kt` (~32), `data/unified/FileBackedUnifiedMetadataEngine.kt` (`sanitizeHardwoodCutlistIndex`, ~1567)

**Interfaces:**
- Produces (used by Tasks 2 and 3): `object HardwoodDocuments { const val SOURCE_KIND_CV_METADATA; fun isMetadataBacked(doc): Boolean; fun isAvailable(doc, pdfExists: (String) -> Boolean): Boolean; fun dropUnknownDocTypes(index: HardwoodCutlistIndex?): HardwoodCutlistIndex?; fun docsOfType(index, type): List<HardwoodDocumentIndex>; fun rowsOfTypeOrNull(index, type): List<HardwoodCutlistRow>?; fun mergedByType(docs: List<HardwoodDocumentIndex>): Map<HardwoodDocType, HardwoodDocumentIndex>; fun mergeSummaries(summaries: List<HardwoodDocSummary>): Map<HardwoodDocType, HardwoodDocSummary>; fun selectDoc(docs, type, batchId: String?, owningRowId: String?): HardwoodDocumentIndex? }`; model fields `HardwoodDocumentIndex.sourceKind/batchId/batchName/revisionId: String`, `.doors: List<HardwoodDoorAssembly>`.

- [ ] **Step 1: Write the failing helper tests**

Create `app/src/test/java/com/kkc/sheettracker/data/HardwoodDocumentsTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocSummary
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwoodDocumentsTest {
    private fun row(id: String, qty: Int = 1) = HardwoodCutlistRow(rowId = id, qty = qty)

    private fun legacy(type: HardwoodDocType, pdf: String = "x.pdf", vararg rows: HardwoodCutlistRow) =
        HardwoodDocumentIndex(docType = type, pdfFilename = pdf, rows = rows.toList())

    private fun batch(type: HardwoodDocType, batchId: String, name: String, vararg rows: HardwoodCutlistRow) =
        HardwoodDocumentIndex(
            docType = type,
            sourceKind = HardwoodDocuments.SOURCE_KIND_CV_METADATA,
            batchId = batchId,
            batchName = name,
            rows = rows.toList()
        )

    @Test
    fun metadataBackedDocIsAvailableWithoutAnyPdf() {
        val doc = batch(HardwoodDocType.FACE_FRAME_CUT_LIST, "b1", "Main")
        assertTrue(HardwoodDocuments.isAvailable(doc) { false })
    }

    @Test
    fun legacyDocNeedsItsPdfToExist() {
        val doc = legacy(HardwoodDocType.NAILER_CUT_LIST, "nailer.pdf")
        assertTrue(HardwoodDocuments.isAvailable(doc) { it == "nailer.pdf" })
        assertFalse(HardwoodDocuments.isAvailable(doc) { false })
        assertFalse(HardwoodDocuments.isAvailable(legacy(HardwoodDocType.NAILER_CUT_LIST, ""), { true }))
    }

    @Test
    fun retiredDoorListIsNeverAvailable() {
        assertFalse(HardwoodDocuments.isAvailable(batch(HardwoodDocType.DOOR_LIST, "b1", "Main")) { true })
        assertFalse(HardwoodDocuments.isAvailable(legacy(HardwoodDocType.DOOR_LIST)) { true })
    }

    @Test
    fun unknownDocTypeIsDroppedWhileKnownDocsSurvive() {
        val json = """
            {"documents":[
              {"docType":"HINGE_ORDER_LIST","rows":[{"rowId":"h1","qty":4}]},
              {"docType":"FACE_FRAME_CUT_LIST","rows":[{"rowId":"f1","qty":2}],"futureKey":{"a":1}}
            ],"futureRoot":true}
        """.trimIndent()
        val decoded = Gson().fromJson(json, HardwoodCutlistIndex::class.java)
        val cleaned = HardwoodDocuments.dropUnknownDocTypes(decoded)
        assertEquals(listOf(HardwoodDocType.FACE_FRAME_CUT_LIST), cleaned?.documents?.map { it.docType })
    }

    @Test
    fun dropUnknownDocTypesHandlesNullAndReturnsSameInstanceWhenNothingDropped() {
        assertNull(HardwoodDocuments.dropUnknownDocTypes(null))
        val index = HardwoodCutlistIndex(documents = listOf(legacy(HardwoodDocType.NAILER_CUT_LIST)))
        assertSame(index, HardwoodDocuments.dropUnknownDocTypes(index))
    }

    @Test
    fun rowsOfTypeConcatenatesBatchesAndIsNullWhenTypeAbsent() {
        val index = HardwoodCutlistIndex(
            documents = listOf(
                batch(HardwoodDocType.CLOSET_ROD_CUT_LIST, "b1", "A", row("cv:b1:a")),
                batch(HardwoodDocType.CLOSET_ROD_CUT_LIST, "b2", "B", row("cv:b2:a"), row("cv:b2:b"))
            )
        )
        assertEquals(
            listOf("cv:b1:a", "cv:b2:a", "cv:b2:b"),
            HardwoodDocuments.rowsOfTypeOrNull(index, HardwoodDocType.CLOSET_ROD_CUT_LIST)?.map { it.rowId }
        )
        assertNull(HardwoodDocuments.rowsOfTypeOrNull(index, HardwoodDocType.DOOR_CUT_LIST))
        assertEquals(
            emptyList<String>(),
            HardwoodDocuments.rowsOfTypeOrNull(
                HardwoodCutlistIndex(documents = listOf(legacy(HardwoodDocType.DOOR_CUT_LIST))),
                HardwoodDocType.DOOR_CUT_LIST
            )?.map { it.rowId }
        )
    }

    @Test
    fun mergedByTypePassesSingleDocsThroughAndMergesBatches() {
        val single = legacy(HardwoodDocType.NAILER_CUT_LIST, "n.pdf", row("n1"))
        val a = batch(HardwoodDocType.FACE_FRAME_CUT_LIST, "b1", "A", row("cv:b1:a"))
        val b = batch(HardwoodDocType.FACE_FRAME_CUT_LIST, "b2", "B", row("cv:b2:a"))
        val merged = HardwoodDocuments.mergedByType(listOf(single, a, b))

        assertSame(single, merged[HardwoodDocType.NAILER_CUT_LIST])
        val face = merged.getValue(HardwoodDocType.FACE_FRAME_CUT_LIST)
        assertEquals(listOf("cv:b1:a", "cv:b2:a"), face.rows.map { it.rowId })
        assertEquals("", face.batchId)
    }

    @Test
    fun mergeSummariesSumsCountsPerType() {
        val merged = HardwoodDocuments.mergeSummaries(
            listOf(
                HardwoodDocSummary(HardwoodDocType.FACE_FRAME_CUT_LIST, rowCount = 2, counts = HardwoodStatusCounts(4, 1, 0, 0)),
                HardwoodDocSummary(HardwoodDocType.FACE_FRAME_CUT_LIST, rowCount = 3, counts = HardwoodStatusCounts(6, 2, 1, 0)),
                HardwoodDocSummary(HardwoodDocType.NAILER_CUT_LIST, rowCount = 1, counts = HardwoodStatusCounts(1, 0, 0, 0))
            )
        )
        val face = merged.getValue(HardwoodDocType.FACE_FRAME_CUT_LIST)
        assertEquals(5, face.rowCount)
        assertEquals(HardwoodStatusCounts(10, 3, 1, 0), face.counts)
        assertEquals(1, merged.getValue(HardwoodDocType.NAILER_CUT_LIST).rowCount)
    }

    @Test
    fun selectDocPrefersBatchThenOwningRowThenFirst() {
        val a = batch(HardwoodDocType.FACE_FRAME_CUT_LIST, "b1", "A", row("cv:b1:a"))
        val b = batch(HardwoodDocType.FACE_FRAME_CUT_LIST, "b2", "B", row("cv:b2:a"))
        val docs = listOf(a, b)
        val type = HardwoodDocType.FACE_FRAME_CUT_LIST

        assertSame(b, HardwoodDocuments.selectDoc(docs, type, "b2", null))
        assertSame(b, HardwoodDocuments.selectDoc(docs, type, null, "cv:b2:a"))
        assertSame(a, HardwoodDocuments.selectDoc(docs, type, null, null))
        assertSame(a, HardwoodDocuments.selectDoc(docs, type, "missing", "also-missing"))
        assertNull(HardwoodDocuments.selectDoc(docs, HardwoodDocType.NAILER_CUT_LIST, "b1", null))
    }
}
```

- [ ] **Step 2: Write the failing engine decode test**

Create `app/src/test/java/com/kkc/sheettracker/data/unified/CvCutlistIndexDecodeTest.kt`:

```kotlin
package com.kkc.sheettracker.data.unified

import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CvCutlistIndexDecodeTest {
    private val folder = "686 - Example"

    private fun documentsFor(indexJson: String): List<HardwoodDocumentIndex> {
        val baseDir = Files.createTempDirectory("cv-cutlist-decode-test").toFile()
        val metadata = File(baseDir, "$folder/.metadata").apply { mkdirs() }
        File(metadata, "deployment_gate.json").writeText("""{"deployed": true}""")
        File(metadata, "hardwoods").mkdirs()
        File(metadata, "hardwoods/cutlist_index.json").writeText(indexJson)
        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)
        val snapshot = engine.getHardwoodsSnapshot(folder)
        assertNotNull("engine produced no hardwoods snapshot for the seeded job", snapshot)
        return snapshot!!.job.index?.documents.orEmpty()
    }

    @Test
    fun legacyIndexDecodesWithEmptyBatchFields() {
        val docs = documentsFor(
            """{"generatedAt":"t","documents":[{"docType":"FACE_FRAME_CUT_LIST","pdfFilename":"f.pdf","pageCount":2,
               "rows":[{"rowId":"r1","page":1,"rowOrdinal":0,"qty":2,"description":"Rail","width":"2","length":"30","cabinets":["1"]}],"totals":[]}]}"""
        )
        val doc = docs.single()
        assertEquals("f.pdf", doc.pdfFilename)
        assertEquals("", doc.sourceKind)
        assertEquals("", doc.batchId)
        assertTrue(doc.doors.isEmpty())
    }

    @Test
    fun metadataDocKeepsBatchFieldsDoorsAndIgnoresFutureKeys() {
        val docs = documentsFor(
            """{"documents":[{"docType":"DOOR_CUT_LIST","sourceKind":"CV_METADATA","batchId":"b1","batchName":"Main","revisionId":"r1",
               "futureDocKey":{"x":1},
               "rows":[{"rowId":"cv:b1:a","page":0,"rowOrdinal":0,"qty":3,"description":"Stile","width":"2","length":"30","cabinets":["1","2"],
                        "components":[{"sourceId":"cvpart:1","cabinet":"1","room":"Kitchen","quantity":3}],"futureRowKey":true}],
               "doors":[{"id":"d1","cabinet":"1","room":"Kitchen","style":"2 1/4\" Flat Panel","width":"15","height":"30","profiles":["a","b"],"finish":"paint"}],
               "totals":[]}],"futureRoot":[1,2]}"""
        )
        val doc = docs.single()
        assertEquals(HardwoodDocType.DOOR_CUT_LIST, doc.docType)
        assertEquals("CV_METADATA", doc.sourceKind)
        assertEquals("b1", doc.batchId)
        assertEquals("Main", doc.batchName)
        assertEquals("r1", doc.revisionId)
        assertEquals("", doc.pdfFilename)
        assertEquals(listOf("1", "2"), doc.rows.single().cabinets)
        val door = doc.doors.single()
        assertEquals("2 1/4\" Flat Panel", door.style)
        assertEquals(listOf("a", "b"), door.profiles)
    }

    @Test
    fun unknownDocTypeIsDroppedNotMislabelled() {
        val docs = documentsFor(
            """{"documents":[{"docType":"HINGE_ORDER_LIST","rows":[{"rowId":"h1","qty":4}]},
                              {"docType":"NAILER_CUT_LIST","pdfFilename":"n.pdf","rows":[{"rowId":"n1","qty":1}]}]}"""
        )
        assertEquals(listOf(HardwoodDocType.NAILER_CUT_LIST), docs.map { it.docType })
    }
}
```

- [ ] **Step 3: Run both to see them fail**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HardwoodDocumentsTest" --tests "com.kkc.sheettracker.data.unified.CvCutlistIndexDecodeTest"`
Expected: compilation FAIL (`HardwoodDocuments`, `sourceKind`, `batchId` ... unresolved).

- [ ] **Step 4: Add the model fields**

In `data/models/Models.kt`, add before `data class HardwoodDocumentIndex` and extend it:

```kotlin
data class HardwoodDoorAssembly(
    val id: String = "",
    val cabinet: String = "",
    val room: String = "",
    val number: String = "",
    val label: String = "",
    val style: String = "",
    val schedule: String = "",
    val width: String = "",
    val height: String = "",
    val hinge: String = "",
    val doorType: String = "",
    val notes: String = "",
    val grain: String = "",
    val profiles: List<String> = emptyList()
)

data class HardwoodDocumentIndex(
    val docType: HardwoodDocType = HardwoodDocType.FACE_FRAME_CUT_LIST,
    val pdfFilename: String = "",
    val pageCount: Int = 0,
    val rows: List<HardwoodCutlistRow> = emptyList(),
    val totals: List<HardwoodTotalsBlock> = emptyList(),
    val sourceKind: String = "",
    val batchId: String = "",
    val batchName: String = "",
    val revisionId: String = "",
    val doors: List<HardwoodDoorAssembly> = emptyList()
)
```

In `data/models/CacheIndexModels.kt` extend `CacheIndexHardwoodsDocType`:

```kotlin
data class CacheIndexHardwoodsDocType(
    val docType: String = "",
    val total: Int = 0,
    val done: Int = 0,
    val bad: Int = 0,
    val skipped: Int = 0,
    val batchId: String = "",
    val batchName: String = ""
)
```

- [ ] **Step 5: Create the helper**

Create `data/HardwoodDocuments.kt`:

```kotlin
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
```

- [ ] **Step 6: Keep the new fields and drop unknown types in the engine's sanitizer**

In `FileBackedUnifiedMetadataEngine.sanitizeHardwoodCutlistIndex`, replace

```kotlin
            documents = (gsonNullable(index.documents) ?: emptyList()).map { doc ->
                HardwoodDocumentIndex(
                    docType = gsonNullable(doc.docType) ?: HardwoodDocType.FACE_FRAME_CUT_LIST,
                    pdfFilename = gsonNullable(doc.pdfFilename) ?: "",
                    pageCount = doc.pageCount,
```
with
```kotlin
            documents = (gsonNullable(index.documents) ?: emptyList())
                // A doc type this build does not know (Gson yields null) is dropped, not relabelled.
                .filter { doc -> gsonNullable(doc.docType) != null }
                .map { doc ->
                HardwoodDocumentIndex(
                    docType = doc.docType,
                    pdfFilename = gsonNullable(doc.pdfFilename) ?: "",
                    pageCount = doc.pageCount,
                    sourceKind = gsonNullable(doc.sourceKind) ?: "",
                    batchId = gsonNullable(doc.batchId) ?: "",
                    batchName = gsonNullable(doc.batchName) ?: "",
                    revisionId = gsonNullable(doc.revisionId) ?: "",
                    doors = (gsonNullable(doc.doors) ?: emptyList()).map { door ->
                        HardwoodDoorAssembly(
                            id = gsonNullable(door.id) ?: "",
                            cabinet = gsonNullable(door.cabinet) ?: "",
                            room = gsonNullable(door.room) ?: "",
                            number = gsonNullable(door.number) ?: "",
                            label = gsonNullable(door.label) ?: "",
                            style = gsonNullable(door.style) ?: "",
                            schedule = gsonNullable(door.schedule) ?: "",
                            width = gsonNullable(door.width) ?: "",
                            height = gsonNullable(door.height) ?: "",
                            hinge = gsonNullable(door.hinge) ?: "",
                            doorType = gsonNullable(door.doorType) ?: "",
                            notes = gsonNullable(door.notes) ?: "",
                            grain = gsonNullable(door.grain) ?: "",
                            profiles = (gsonNullable(door.profiles) ?: emptyList()).map { gsonNullable(it) ?: "" }
                        )
                    },
```
(the following `rows = ...` and `totals = ...` arguments stay as they are). Add `import com.kkc.sheettracker.data.models.HardwoodDoorAssembly` to the file.

- [ ] **Step 7: Run both test classes to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HardwoodDocumentsTest" --tests "com.kkc.sheettracker.data.unified.CvCutlistIndexDecodeTest"`
Expected: PASS. If `CvCutlistIndexDecodeTest` fails only on "engine produced no hardwoods snapshot", seed what `UnifiedMetadataEngineTest.seedJob` seeds for that job (job info / static data prerequisites) in `documentsFor`, keeping every assertion.

- [ ] **Step 8: Run the neighbouring existing tests**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.unified.*" --tests "com.kkc.sheettracker.data.HardwoodsProgressStoreTest"`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/HardwoodDocuments.kt app/src/main/java/com/kkc/sheettracker/data/models/Models.kt app/src/main/java/com/kkc/sheettracker/data/models/CacheIndexModels.kt app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt app/src/test/java/com/kkc/sheettracker/data/HardwoodDocumentsTest.kt app/src/test/java/com/kkc/sheettracker/data/unified/CvCutlistIndexDecodeTest.kt
git commit -m "feat(hardwoods): decode metadata-backed cutlist docs with batch fields; drop unknown doc types" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Roll-ups across batches, availability in job detail, progress isolation

**Files:**
- Modify: `data/SpecialtyStateStore.kt` (~224 and ~256), `ui/components/doorcutlist/DoorPanelCutListData.kt` (~340), `ui/specialty/SpecialtyDoorPanelsScreen.kt` (~276), `ui/jobs/UnifiedModeSpecs.kt` (~209), `ui/hardwoods/HardwoodsJobDetailScreen.kt` (~219-260)
- Create: `app/src/test/java/com/kkc/sheettracker/data/MetadataBatchRollupTest.kt`

**Interfaces:**
- Consumes: Task 1 `HardwoodDocuments.rowsOfTypeOrNull`, `mergedByType`, `mergeSummaries`, `isAvailable`.
- Produces: no new public API; behavior only.

- [ ] **Step 1: Write the failing roll-up and isolation test**

Create `app/src/test/java/com/kkc/sheettracker/data/MetadataBatchRollupTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodJob
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MetadataBatchRollupTest {
    private val folder = "686 - Example"
    private val tabletId = "tablet-test"

    private fun batchDoc(batchId: String, name: String, vararg rows: HardwoodCutlistRow) = HardwoodDocumentIndex(
        docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
        sourceKind = HardwoodDocuments.SOURCE_KIND_CV_METADATA,
        batchId = batchId,
        batchName = name,
        rows = rows.toList()
    )

    private fun twoBatchIndex() = HardwoodCutlistIndex(
        documents = listOf(
            batchDoc("b1", "Main", HardwoodCutlistRow(rowId = "cv:b1:a", qty = 2)),
            batchDoc("b2", "Second", HardwoodCutlistRow(rowId = "cv:b2:a", qty = 2))
        )
    )

    private fun newStore(index: HardwoodCutlistIndex): Pair<HardwoodsProgressStore, HardwoodJob> {
        val baseDir = Files.createTempDirectory("cv-rollup-test").toFile()
        val path = File(baseDir, "$folder/.metadata/hardwoods/cutlist_index.json")
        path.parentFile.mkdirs()
        path.writeText(Gson().toJson(index))
        return HardwoodsProgressStore(baseDir, tabletId) to
            HardwoodJob(folderName = folder, jobNumber = "686", jobName = "Example", index = index)
    }

    @Test
    fun completingARowInOneBatchDoesNotTouchTheOther() {
        val (store, job) = newStore(twoBatchIndex())
        store.incrementDoneCount(jobFolderName = folder, docType = "FACE_FRAME_CUT_LIST", rowId = "cv:b1:a", qty = 2)

        val summary = store.summarizeJob(job)
        assertEquals(listOf(1, 0), summary.documents.map { it.counts.donePieces })
        assertEquals(4, summary.counts.totalPieces)
        assertEquals(1, summary.counts.donePieces)
    }

    @Test
    fun perTypeSummaryMergeEqualsTheJobTotals() {
        val (store, job) = newStore(twoBatchIndex())
        store.incrementDoneCount(jobFolderName = folder, docType = "FACE_FRAME_CUT_LIST", rowId = "cv:b2:a", qty = 2)

        val summary = store.summarizeJob(job)
        val merged = HardwoodDocuments.mergeSummaries(summary.documents).getValue(HardwoodDocType.FACE_FRAME_CUT_LIST)
        assertEquals(summary.counts, merged.counts)
        assertEquals(2, merged.rowCount)
    }

    @Test
    fun closetRodRowsComeFromEveryBatch() {
        val index = HardwoodCutlistIndex(
            documents = listOf(
                HardwoodDocumentIndex(docType = HardwoodDocType.CLOSET_ROD_CUT_LIST, batchId = "b1", rows = listOf(HardwoodCutlistRow(rowId = "cv:b1:r", qty = 1))),
                HardwoodDocumentIndex(docType = HardwoodDocType.CLOSET_ROD_CUT_LIST, batchId = "b2", rows = listOf(HardwoodCutlistRow(rowId = "cv:b2:r", qty = 2)))
            )
        )
        assertEquals(
            listOf("cv:b1:r", "cv:b2:r"),
            HardwoodDocuments.rowsOfTypeOrNull(index, HardwoodDocType.CLOSET_ROD_CUT_LIST)?.map { it.rowId }
        )
    }
}
```

- [ ] **Step 2: Run it**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.MetadataBatchRollupTest"`
Expected: PASS (this proves the existing progress store already isolates batches by row ID; Task 1 provides the helpers). If `incrementDoneCount` has a different parameter list, adapt the call to the real signature (named parameters appear in `HardwoodsWorkspaceScreen.kt` ~1587); keep both assertions.

- [ ] **Step 3: Switch the roll-up readers to all batches**

`data/SpecialtyStateStore.kt` closet rods — replace

```kotlin
            val closetRows = hardwoodIndex.documents
                .firstOrNull { it.docType == HardwoodDocType.CLOSET_ROD_CUT_LIST }
                ?.rows
                ?: return
```
with
```kotlin
            val closetRows = HardwoodDocuments.rowsOfTypeOrNull(hardwoodIndex, HardwoodDocType.CLOSET_ROD_CUT_LIST)
                ?: return
```
and door panels — replace
```kotlin
            val doorCutRows = hardwoodIndex.documents
                .firstOrNull { it.docType == HardwoodDocType.DOOR_CUT_LIST }
                ?.rows
                ?: return
```
with
```kotlin
            val doorCutRows = HardwoodDocuments.rowsOfTypeOrNull(hardwoodIndex, HardwoodDocType.DOOR_CUT_LIST)
                ?: return
```

`ui/components/doorcutlist/DoorPanelCutListData.kt` — replace

```kotlin
    val doorDoc = index.documents.orEmpty().firstOrNull { it.docType == HardwoodDocType.DOOR_CUT_LIST }
        ?: return null
```
with
```kotlin
    val doorDoc = HardwoodDocuments.mergedByType(index.documents.orEmpty())[HardwoodDocType.DOOR_CUT_LIST]
        ?: return null
```

`ui/specialty/SpecialtyDoorPanelsScreen.kt` (`buildDoorPanelsViewModel`) — replace
```kotlin
    val docs = hardwoodIndex?.documents.orEmpty().associateBy { it.docType }
```
with
```kotlin
    val docs = HardwoodDocuments.mergedByType(hardwoodIndex?.documents.orEmpty())
```

Add `import com.kkc.sheettracker.data.HardwoodDocuments` to each of the three files (skip if already imported via wildcard).

- [ ] **Step 4: Job detail availability and per-type maps**

In `ui/hardwoods/HardwoodsJobDetailScreen.kt`:

1. Replace the `docsByType` definition
```kotlin
    val docsByType = remember(resolvedJob.index) {
        resolvedJob.index?.documents.orEmpty().associateBy { it.docType }
    }
```
with
```kotlin
    val docsByType = remember(resolvedJob.index) {
        HardwoodDocuments.mergedByType(resolvedJob.index?.documents.orEmpty())
    }
```
2. Replace the gate inside `availableDocsByType`
```kotlin
            docsByType.filterValues { doc ->
                // Door lists are retired; legacy jobs may still carry one in their index.
                doc.docType != HardwoodDocType.DOOR_LIST &&
                    doc.pdfFilename.isNotBlank() &&
                    jobRepository.getJobRootPdfFile(
                        jobFolderName = jobFolderName,
                        pdfFilename = doc.pdfFilename,
                        preferDarkMode = false
                    ) != null
            }
```
with
```kotlin
            docsByType.filterValues { doc ->
                // Door lists are retired; metadata-backed docs need no PDF.
                HardwoodDocuments.isAvailable(doc) { pdfFilename ->
                    jobRepository.getJobRootPdfFile(
                        jobFolderName = jobFolderName,
                        pdfFilename = pdfFilename,
                        preferDarkMode = false
                    ) != null
                }
            }
```
3. Replace the two other maps:
```kotlin
        summary.documents.associateBy { it.docType }
```
with `HardwoodDocuments.mergeSummaries(summary.documents)` and
```kotlin
        progressJob.index?.documents.orEmpty().associateBy { it.docType }
```
with `HardwoodDocuments.mergedByType(progressJob.index?.documents.orEmpty())`.
4. Add `import com.kkc.sheettracker.data.HardwoodDocuments`.

- [ ] **Step 5: Label batches in job-list progress segments**

In `ui/jobs/UnifiedModeSpecs.kt` replace `materialName = docType.docType,` (inside `hwProgress.docTypes.map`) with
```kotlin
                                materialName = if (docType.batchName.isBlank()) docType.docType else "${docType.docType} — ${docType.batchName}",
```

- [ ] **Step 6: Compile and run the affected test groups**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.*" --tests "com.kkc.sheettracker.ui.*"`
Expected: PASS (compiles every touched file; existing specialty/door-panel/job-list tests unchanged).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/SpecialtyStateStore.kt app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListData.kt app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyDoorPanelsScreen.kt app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedModeSpecs.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobDetailScreen.kt app/src/test/java/com/kkc/sheettracker/data/MetadataBatchRollupTest.kt
git commit -m "feat(hardwoods): roll up cutlist docs across batches; metadata docs need no PDF in job detail" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Workspace — availability, selected batch, chips and door list

**Files:**
- Create: `ui/hardwoods/HardwoodsBatchUi.kt`, `app/src/test/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsBatchUiTest.kt`
- Modify: `ui/hardwoods/HardwoodsWorkspaceScreen.kt` (~603-634, ~772-786, ~1488)

**Interfaces:**
- Consumes: Task 1 `HardwoodDocuments.isAvailable`, `selectDoc`; model `HardwoodDoorAssembly`, `HardwoodDocumentIndex.batchId/batchName/doors`.
- Produces: `fun hardwoodsBatchLabel(doc): String`, `fun hardwoodsDoorSummaryLine(door): String`, composables `HardwoodsBatchChips(docs, selected, onSelect)` and `HardwoodsDoorAssembliesPanel(doors)`.

- [ ] **Step 1: Write the failing UI-helper test**

Create `app/src/test/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsBatchUiTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodDoorAssembly
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwoodsBatchUiTest {
    @Test
    fun batchLabelUsesTheBatchNameOrDefault() {
        assertEquals("Main", hardwoodsBatchLabel(HardwoodDocumentIndex(batchId = "b1", batchName = "Main")))
        assertEquals("Default", hardwoodsBatchLabel(HardwoodDocumentIndex(batchId = "b2", batchName = " ")))
    }

    @Test
    fun doorSummaryJoinsOnlyTheFieldsThatArePresent() {
        val full = HardwoodDoorAssembly(
            label = "D1", cabinet = "12", room = "Kitchen", width = "15", height = "30",
            style = "2 1/4\" Flat Panel", hinge = "Blum", grain = "Vertical", notes = "Paint grade"
        )
        assertEquals(
            "D1 · Cab 12 · Kitchen · 15 × 30 · 2 1/4\" Flat Panel · hinge Blum · grain Vertical · Paint grade",
            hardwoodsDoorSummaryLine(full)
        )
        assertEquals("Door · Cab 3", hardwoodsDoorSummaryLine(HardwoodDoorAssembly(cabinet = "3")))
        assertEquals("Door", hardwoodsDoorSummaryLine(HardwoodDoorAssembly(width = "15")))
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.hardwoods.HardwoodsBatchUiTest"`
Expected: compilation FAIL (`hardwoodsBatchLabel` unresolved).

- [ ] **Step 3: Create the batch UI file**

Create `ui/hardwoods/HardwoodsBatchUi.kt`:

```kotlin
package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodDoorAssembly

fun hardwoodsBatchLabel(doc: HardwoodDocumentIndex): String = doc.batchName.ifBlank { "Default" }

fun hardwoodsDoorSummaryLine(door: HardwoodDoorAssembly): String {
    val parts = buildList {
        add(door.label.ifBlank { "Door" })
        if (door.cabinet.isNotBlank()) add("Cab ${door.cabinet}")
        if (door.room.isNotBlank()) add(door.room)
        if (door.width.isNotBlank() && door.height.isNotBlank()) add("${door.width} × ${door.height}")
        if (door.style.isNotBlank()) add(door.style)
        if (door.hinge.isNotBlank()) add("hinge ${door.hinge}")
        if (door.grain.isNotBlank()) add("grain ${door.grain}")
        if (door.notes.isNotBlank()) add(door.notes)
    }
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwoodsBatchChips(
    docs: List<HardwoodDocumentIndex>,
    selected: HardwoodDocumentIndex?,
    onSelect: (HardwoodDocumentIndex) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        docs.forEach { doc ->
            FilterChip(
                selected = doc.batchId == selected?.batchId,
                onClick = { onSelect(doc) },
                label = { Text(hardwoodsBatchLabel(doc)) }
            )
        }
    }
}

@Composable
fun HardwoodsDoorAssembliesPanel(
    doors: List<HardwoodDoorAssembly>,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide doors (${doors.size})" else "Show doors (${doors.size})")
        }
        if (expanded) {
            doors.forEach { door ->
                Text(
                    hardwoodsDoorSummaryLine(door),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
```

- [ ] **Step 4: Run the UI-helper test**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.hardwoods.HardwoodsBatchUiTest"`
Expected: PASS.

- [ ] **Step 5: Wire the workspace**

In `ui/hardwoods/HardwoodsWorkspaceScreen.kt`:

1. Replace the availability filter inside `LaunchedEffect(documents, jobFolderName, isDarkTheme)`:
```kotlin
            documents.filter { doc ->
                // Door lists are retired; legacy jobs may still carry one in their index.
                doc.docType != HardwoodDocType.DOOR_LIST &&
                    doc.pdfFilename.isNotBlank() &&
                    jobRepository.getJobRootPdfFile(
                        jobFolderName = jobFolderName,
                        pdfFilename = doc.pdfFilename,
                        preferDarkMode = isDarkTheme
                    ) != null
            }
```
with
```kotlin
            documents.filter { doc ->
                // Door lists are retired; metadata-backed docs need no PDF.
                HardwoodDocuments.isAvailable(doc) { pdfFilename ->
                    jobRepository.getJobRootPdfFile(
                        jobFolderName = jobFolderName,
                        pdfFilename = pdfFilename,
                        preferDarkMode = isDarkTheme
                    ) != null
                }
            }
```
2. Replace
```kotlin
    val selectedDoc = remember(availableDocuments, selectedDocType) { availableDocuments.firstOrNull { it.docType == selectedDocType } }
```
with
```kotlin
    var selectedBatchId by rememberSaveable(jobFolderName) { mutableStateOf<String?>(null) }
    val selectedDoc = remember(availableDocuments, selectedDocType, selectedBatchId, initialRowId) {
        HardwoodDocuments.selectDoc(availableDocuments, selectedDocType, selectedBatchId, initialRowId)
    }
```
3. Replace the `pendingChangedByDoc` block
```kotlin
        availableDocuments.associate { doc ->
            val pending = doc.rows.filter { row ->
                val state = rowRevisionStateMap[doc.docType.name to row.rowId] ?: return@filter false
                if (state.latestRevision <= 0) return@filter false
                val qty = row.qty.coerceAtLeast(0)
                val done = rowProgressMap[doc.docType.name to row.rowId]?.doneCount ?: 0
                qty > 0 && done < qty
            }.map { it.rowId }.toSet()
            doc.docType to pending
        }
```
with
```kotlin
        availableDocuments.groupBy { it.docType }.mapValues { (docType, docs) ->
            docs.flatMap { doc ->
                doc.rows.filter { row ->
                    val state = rowRevisionStateMap[docType.name to row.rowId] ?: return@filter false
                    if (state.latestRevision <= 0) return@filter false
                    val qty = row.qty.coerceAtLeast(0)
                    val done = rowProgressMap[docType.name to row.rowId]?.doneCount ?: 0
                    qty > 0 && done < qty
                }.map { it.rowId }
            }.toSet()
        }
```
4. Immediately before
```kotlin
                if (isDoorCutListView && !showRipCutList && selectedDoc != null) {
                    Spacer(Modifier.height(2.dp))
```
insert
```kotlin
                val docsOfSelectedType = availableDocuments.filter { it.docType == selectedDocType }
                if (!showRipCutList && docsOfSelectedType.size > 1) {
                    HardwoodsBatchChips(
                        docs = docsOfSelectedType,
                        selected = selectedDoc,
                        onSelect = { selectedBatchId = it.batchId }
                    )
                }
                if (!showRipCutList && selectedDoc != null && selectedDoc.doors.isNotEmpty()) {
                    HardwoodsDoorAssembliesPanel(doors = selectedDoc.doors)
                }
```
5. Add `import com.kkc.sheettracker.data.HardwoodDocuments` (check for an existing wildcard `com.kkc.sheettracker.data.*` first).

- [ ] **Step 6: Compile and run the UI and data test groups**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.*" --tests "com.kkc.sheettracker.data.*" --tests "com.kkc.sheettracker.navigation.*"`
Expected: PASS. A compile error in the workspace edit means an anchor differs from this plan's text; fix the edit, never the test.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsBatchUi.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt app/src/test/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsBatchUiTest.kt
git commit -m "feat(hardwoods): workspace shows metadata cutlists with batch chips and door list" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Full verification and release candidate

**Files:** none (verification only); optional note in the spec's change log.

- [ ] **Step 1: Run the entire unit suite**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: all tests pass. Any pre-existing failure must be reproduced on the base commit before being set aside, and reported by name.

- [ ] **Step 2: Build the debug APK**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL; note the APK path printed under `app/build/outputs/apk/debug/`.

- [ ] **Step 3: Real-data decode check**

Using a temp copy of the Hours Tracker worker scratch output (publish a metadata-only job with `backend/tests/cv_export_support.py` helpers, or copy the JSON from the worker's test fixtures), place `cutlist_index.json` under a temp job folder and run it through `CvCutlistIndexDecodeTest`'s `documentsFor` by temporarily pointing a scratch test at it; confirm doc count, batch fields, `cv:` row IDs and door count match the worker output. Delete the scratch test afterwards.

- [ ] **Step 4: Write the release note**

Create `docs/superpowers/reports/2026-10-14-metadata-only-cutlists-tablet-release-note.md` listing: commits, test counts from Steps 1-2, the APK path, what is verified by unit tests versus what needs a human check on a test tablet (metadata-only job visible in Hardwoods and Specialty lists, active and archived routes, rows completable offline, batch chips switch tables, door list expands, legacy PDF job unchanged), and the explicit statement that nothing was installed on any device and `adb uninstall` was not used.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/reports/2026-10-14-metadata-only-cutlists-tablet-release-note.md
git commit -m "docs: metadata-only cutlists tablet release note" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:** T1 model → Task 1 Step 4. T2 unknown-type drop → Task 1 Steps 5-6 (engine sanitize + `dropUnknownDocTypes` for raw-JSON readers; Task 2 Step 3 readers use `rowsOfTypeOrNull`/`mergedByType`, which compare by equality so a null `docType` never matches). T3 availability → Task 2 Step 4 (job detail) and Task 3 Step 5.1 (workspace). T4 batch identity/selection by owning row → `selectDoc` (Task 1) wired in Task 3 Step 5.2. T5 chips → Task 3. T6 roll-ups → Task 2 Steps 3-5. T7 progress isolation → `MetadataBatchRollupTest` (Task 2). T8 door list → Task 3 Steps 3 and 5.4. T9 forward compat → Task 1 tests (future keys ignored, unknown type dropped). Spec §5 verification → Tasks 1-4. §6 constraints → Global Constraints.

**Placeholder scan:** none; each conditional (engine seed, `incrementDoneCount` parameter names, workspace anchors) names the exact fix path and forbids weakening assertions.

**Type consistency:** `HardwoodDocuments` method names/signatures identical across Tasks 1-3; model fields `sourceKind/batchId/batchName/revisionId/doors` consistent; `CacheIndexHardwoodsDocType.batchName` used in Task 2 Step 5 and defined in Task 1 Step 4.

**Known limits:** rip-totals tab stays empty for metadata docs (worker publishes `totals: []`); `completedRemovedRowsForDocument` and collapsed-section state are keyed by `docType`, so they are shared across batches of a type (cosmetic). Real-device verification is a human step.
