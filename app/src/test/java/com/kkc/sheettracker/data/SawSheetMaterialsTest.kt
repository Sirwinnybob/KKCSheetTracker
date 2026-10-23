package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialMetadata
import com.kkc.sheettracker.data.models.PageMetadata
import org.junit.Assert.*
import org.junit.Test

class SawSheetMaterialsTest {
    @Test fun allJobSheetMaterialsAppearWithoutRequiringSawItemsOrAmounts() {
        val rows = buildSawSheetMaterials(listOf("Maple PLY", "Oak PLY", " maple ply "),
            listOf(Material("Maple.pdf", "Maple PLY", 5), Material("Walnut.pdf", "Walnut PLY", 4)),
            MaterialMappings.of(emptyMap())) { material -> if (material.pdfFilename == "Maple.pdf") 3 else 1 }
        assertEquals(listOf(SawSheetMaterial("Maple PLY", 3), SawSheetMaterial("Oak PLY", 0)), rows)
    }

    @Test fun cncRemakeMiscAndCustomLabelsCannotCreateMaterialRows() {
        val rows = buildSawSheetMaterials(listOf("3/4 Paint Grade Ply"), listOf(
            Material("main.pdf", "3/4 Paint Grade Ply", 2),
            Material("remake.pdf", "REMAKE 3/4 Paint Grade Ply", 1),
            Material("misc.pdf", "MISC job material", 1),
            Material("custom.pdf", "Plywood Slab Horizontal (Maple)", 1)
        ), MaterialMappings.of(emptyMap())) { 1 }
        assertEquals(listOf(SawSheetMaterial("3/4 Paint Grade Ply", 1)), rows)
        assertTrue(buildSawSheetMaterials(emptyList(), listOf(Material("a.pdf", "Maple", 1)),
            MaterialMappings.of(emptyMap())) { 1 }.isEmpty())
    }

    @Test fun mappedMaterialsMergeButDifferentThicknessesRemainSeparate() {
        val rows = buildSawSheetMaterials(listOf("3/4 Maple PLY", "1/4 Maple PLY"),
            listOf(Material("main.pdf", "Maple sheet", 5), Material("remake.pdf", "Maple sheet", 1), Material("main.pdf", "Maple sheet", 5)),
            MaterialMappings.of(mapOf("Maple sheet" to "3/4 Maple PLY"))) { if (it.pdfFilename == "main.pdf") 3 else 1 }
        assertEquals(listOf(SawSheetMaterial("1/4 Maple PLY", 0), SawSheetMaterial("3/4 Maple PLY", 4)), rows)
    }

    @Test fun unavailableCncProgressIsNotReportedAsZero() {
        assertEquals(listOf(SawSheetMaterial("Maple", null)),
            buildSawSheetMaterials(listOf("Maple"), emptyList(), MaterialMappings.of(emptyMap()), null))
    }

    @Test fun catalogUsesDeclaredSheetUnitsAndNeverCalculatesSawUsageFromArea() {
        val json = """{"materialSummary":[{"material":"Oak PLY","unit":"Sheet","sqFt":100,"orderQuantity":9},
            {"material":"Maple lumber","unit":"BD FT"}],"documents":[{"rows":[
            {"material":"Door panel","unitType":"SHEETS"},{"material":"Frame","unitType":"BD_FT"}]}]}"""
        assertEquals(listOf("Oak PLY"), publishedSheetMaterialNames(json))
        assertTrue(publishedSheetMaterialNames("invalid").isEmpty())
        assertTrue(publishedSheetMaterialNames(json, requiredSchemaVersion = 2).isEmpty())
        assertEquals(listOf("Oak PLY"), publishedSheetMaterialNames(json.replaceFirst("{", "{\"schemaVersion\":2,"), requiredSchemaVersion = 2))
    }

    @Test fun cvJobSummaryWinsOverStalePublishedSummariesAndAnEmptySummaryStaysEmpty() {
        val dir = java.nio.file.Files.createTempDirectory("saw-cv-source").toFile()
        val metadata = java.io.File(dir, "123 - Test/.metadata")
        fun write(path: String, json: String) = java.io.File(metadata, path).apply {
            parentFile!!.mkdirs()
            writeText(json)
        }
        write("hardwoods/cutlist_index.json", """{"materialSummary":[{"material":"Stale worker sheet","unit":"Sheet"}]}""")
        write("cache_static.json", """{"materialSummary":[{"material":"Stale cache sheet","unit":"Sheet"}]}""")
        val cv = write("cv_job/job_data.json", """{"schemaVersion":2,"materialSummary":[
            {"material":"1/4 MDF","unit":"Sheet"},{"material":"3/4 Paint Grade Wood","unit":"BD FT"},
            {"material":"Shelf Pin","unit":"Each"},{"material":"Paint Grade EB Thin","unit":"Per FT"}],
            "fullMaterialSummary":[{"material":"Other phase sheet","unit":"Sheet"}]}""")
        assertEquals(listOf("1/4 MDF"), loadPublishedSheetMaterials(dir, "123 - Test"))
        cv.writeText("""{"schemaVersion":2,"materialSummary":[]}""")
        assertTrue(loadPublishedSheetMaterials(dir, "123 - Test").isEmpty())
        cv.writeText("""{"schemaVersion":2,"materialSummary":[{"material":"Lumber only","unit":"BD FT"}]}""")
        assertTrue(loadPublishedSheetMaterials(dir, "123 - Test").isEmpty())
    }

    @Test fun unavailableCvSummaryUsesOnePublishedSummaryWithoutUnioningSources() {
        val dir = java.nio.file.Files.createTempDirectory("saw-cv-fallback").toFile()
        val metadata = java.io.File(dir, "123 - Test/.metadata")
        fun write(path: String, json: String) = java.io.File(metadata, path).apply {
            parentFile!!.mkdirs()
            writeText(json)
        }
        val index = write("hardwoods/cutlist_index.json", """{"materialSummary":[{"material":"Worker sheet","unit":"Sheet"}]}""")
        write("cache_static.json", """{"materialSummary":[{"material":"Cache sheet","unit":"Sheet"}]}""")
        assertEquals(listOf("Worker sheet"), loadPublishedSheetMaterials(dir, "123 - Test"))
        val cv = write("cv_job/job_data.json", """{"schemaVersion":99,"materialSummary":[{"material":"Unknown schema","unit":"Sheet"}]}""")
        assertEquals(listOf("Worker sheet"), loadPublishedSheetMaterials(dir, "123 - Test"))
        cv.writeText("partial sync")
        assertEquals(listOf("Worker sheet"), loadPublishedSheetMaterials(dir, "123 - Test"))
        index.writeText("""{"documents":[{"rows":[{"material":"Cutlist label","unitType":"SHEETS"}]}]}""")
        assertEquals(listOf("Cache sheet"), loadPublishedSheetMaterials(dir, "123 - Test"))
        index.writeText("""{"materialSummary":[]}""")
        assertTrue(loadPublishedSheetMaterials(dir, "123 - Test").isEmpty())
    }

    @Test fun cncUsageExcludesContinuationsHiddenAndExcludedPagesEvenIfMarkedComplete() {
        val material = Material("a.pdf", "Maple", 5, metadata = MaterialMetadata(pages = listOf(
            PageMetadata(pageNumber = 1), PageMetadata(pageNumber = 2, isPartListContinuation = true),
            PageMetadata(pageNumber = 3, hiddenInApp = true), PageMetadata(pageNumber = 4, trackingExcluded = true),
            PageMetadata(pageNumber = 5))))
        assertEquals(2, usedCncSheets(material) { true })
        assertEquals(1, usedCncSheets(material) { it == 1 })
        assertEquals(0, usedCncSheets(material.copy(metadata = MaterialMetadata(pages = listOf(
            PageMetadata(pageNumber = 1, trackingExcluded = true))))) { true })
    }

    @Test fun sharedHidingPreservesTalliesAndCanBeUndoneFromAnotherTablet() {
        val dir = java.nio.file.Files.createTempDirectory("saw-hide").toFile()
        java.io.File(dir, "123 - Test/.metadata").mkdirs()
        val store = HardwoodsProgressStore(dir, "a")
        store.adjustSheetUsage("123 - Test", "Maple", 1)
        store.adjustSheetUsage("123 - Test", "Maple", 1)
        store.setSheetUsageHidden("123 - Test", "Maple", true)
        store.awaitPendingWrites()
        val peer = HardwoodsProgressStore(dir, "b")
        assertEquals(2, peer.getTotalsRip10DoneMap("123 - Test")[sheetUsageKey("Maple")])
        assertEquals(1, peer.getTotalsRip10DoneMap("123 - Test")[sheetUsageHiddenKey("Maple")])
        peer.setSheetUsageHidden("123 - Test", "Maple", false)
        peer.awaitPendingWrites()
        val reload = HardwoodsProgressStore(dir, "a", readOnly = true)
        assertEquals(0, reload.getTotalsRip10DoneMap("123 - Test")[sheetUsageHiddenKey("Maple")])
        assertEquals(2, reload.getTotalsRip10DoneMap("123 - Test")[sheetUsageKey("Maple")])
    }
}
