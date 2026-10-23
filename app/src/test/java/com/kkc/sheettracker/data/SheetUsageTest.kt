package com.kkc.sheettracker.data

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
