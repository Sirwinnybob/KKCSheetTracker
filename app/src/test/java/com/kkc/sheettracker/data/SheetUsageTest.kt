package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SheetUsageTest {
    @Test fun onlyWholeNonnegativeNumbersAreAccepted() {
        listOf("1.5", "-1", "1e2", "+3", "2 sheets", " 2", "2147483648", "").forEach {
            assertNull(parseWholeSheets(it))
        }
        assertEquals(0, parseWholeSheets("0"))
        assertEquals(12, parseWholeSheets("12"))
    }

    @Test fun countsStartUnenteredAndSurvivePeerReloadByMaterial() {
        val dir = Files.createTempDirectory("sheet-usage").toFile()
        java.io.File(dir, "123 - Test/.metadata").mkdirs()
        val store = HardwoodsProgressStore(dir, "a")
        assertNull(store.getTotalsRip10DoneMap("123 - Test")[sheetUsageKey("Maple PLY")])
        store.setSheetUsage("123 - Test", "Maple PLY", 3)
        store.setSheetUsage("123 - Test", "Oak PLY", 0)
        store.awaitPendingWrites()
        val peer = HardwoodsProgressStore(dir, "b", readOnly = true)
        assertEquals(3, peer.getTotalsRip10DoneMap("123 - Test")[sheetUsageKey(" maple ply ")])
        assertEquals(0, peer.getTotalsRip10DoneMap("123 - Test")[sheetUsageKey("Oak PLY")])
        assertThrows(IllegalArgumentException::class.java) { store.setSheetUsage("123 - Test", "Oak PLY", -1) }
    }

    @Test fun sawEligibilityUsesLinkedSlabEvidenceInsteadOfTheItemName() {
        val rows = listOf(HardwoodCutlistRow(rowId = "slab", material = "Maple PLY", description = "Plywood Slab Vertical", unitType = "SHEETS", cabinets = listOf("22")))
        val item = SpecialtyItem(id = "back", name = "Island Back", material = "Maple PLY", cabinetNumbers = listOf("22"), stations = listOf(SpecialtyStation.SAW))
        assertEquals("Maple PLY", sheetUsageMaterialForItem(item, rows, MaterialMappings.of(emptyMap())))
        assertEquals("Maple PLY", sheetUsageMaterialForItem(item.copy(name = "Custom hood surround"), rows, MaterialMappings.of(emptyMap())))
        assertNull(sheetUsageMaterialForItem(item.copy(name = "Paneled Island Back"), rows.map { it.copy(description = "Raised Panel") }, MaterialMappings.of(emptyMap())))
        assertNull(sheetUsageMaterialForItem(item, emptyList(), MaterialMappings.of(emptyMap())))
        assertNull(sheetUsageMaterialForItem(item.copy(cabinetNumbers = listOf("23")), rows, MaterialMappings.of(emptyMap())))
        assertNull(sheetUsageMaterialForItem(item.copy(stations = listOf(SpecialtyStation.ASSEMBLY)), rows, MaterialMappings.of(emptyMap())))
    }

    @Test fun anyCustomSawItemWithAnExplicitPlywoodSlabMaterialQualifiesWithoutCutlistLinks() {
        val item = SpecialtyItem(id = "custom", name = "Reception desk", material = "Plywood Slab Horizontal (Maple)",
            stations = listOf(SpecialtyStation.SAW))
        assertEquals("Plywood Slab Horizontal (Maple)", sheetUsageMaterialForItem(item, emptyList(), MaterialMappings.of(emptyMap())))
        assertNull(sheetUsageMaterialForItem(item.copy(material = "Maple PLY"), emptyList(), MaterialMappings.of(emptyMap())))
        assertNull(sheetUsageMaterialForItem(item.copy(category = SpecialtyItemCategory.TO_ORDER), emptyList(), MaterialMappings.of(emptyMap())))
        assertNull(sheetUsageMaterialForItem(item.copy(stations = listOf(SpecialtyStation.ASSEMBLY)), emptyList(), MaterialMappings.of(emptyMap())))
    }

    @Test fun completionDoesNotRequireOrCreateASheetUsageEntry() {
        val dir = Files.createTempDirectory("optional-sheets").toFile()
        java.io.File(dir, "123 - Test/.metadata").mkdirs()
        val store = HardwoodsProgressStore(dir, "a")
        store.setDoneCount("123 - Test", "DOOR_CUT_LIST", "panel", qty = 1, doneCount = 1)
        store.awaitPendingWrites()
        val peer = HardwoodsProgressStore(dir, "b", readOnly = true)
        assertEquals(1, peer.getRowProgress("123 - Test", "DOOR_CUT_LIST", "panel").doneCount)
        assertNull(peer.getTotalsRip10DoneMap("123 - Test")[sheetUsageKey("Maple PLY")])
    }
}
