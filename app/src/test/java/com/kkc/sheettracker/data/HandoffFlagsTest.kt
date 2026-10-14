package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.AdminBoardStockItem
import com.kkc.sheettracker.data.models.HandoffCount
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.SpecialtyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HandoffFlagsTest {
    private fun specialty(id: String) = SpecialtyItem(id = id, name = "Item")
    private fun board(id: String) = AdminBoardStockItem(
        id = id, material = "Maple", name = "Crown", feet = 10.0, mode = "bd_ft",
        ripLength = 10, createdAt = "", createdBy = ""
    )

    @Test
    fun mapsAllFiveItemKinds() {
        // 1. board stock row: trimmed id
        assertEquals(HandoffFlagRef("board_stock", "5f0c"), handoffFlagRef(board(" 5f0c ")))
        // 2. office specialty item, raw uuid and default:<i>:<name>
        assertEquals(HandoffFlagRef("specialty_items", "9a1e-uuid"), handoffFlagRef(specialty("9a1e-uuid")))
        assertEquals(HandoffFlagRef("specialty_items", "default:0:bumps"), handoffFlagRef(specialty("default:0:bumps")))
        // 3. tablet-created item keeps the tablet: prefix
        assertEquals(HandoffFlagRef("specialty_items", "tablet:abc"), handoffFlagRef(specialty("tablet:abc")))
        // 4. office item a tablet edited: merge keeps the office id, so it maps to the office id
        assertEquals(HandoffFlagRef("specialty_items", "office-1"), handoffFlagRef(specialty("office-1")))
        // 5. checklist row shown in Specialty: strip KST's checklist: prefix
        assertEquals(HandoffFlagRef("checklist", "c-123"), handoffFlagRef(specialty("checklist:c-123")))
    }

    @Test
    fun specialtyIdsAreTrimmedOnEveryBranch() {
        assertEquals(HandoffFlagRef("specialty_items", "9a1e-uuid"), handoffFlagRef(specialty("  9a1e-uuid\t")))
        assertEquals(HandoffFlagRef("specialty_items", "tablet:abc"), handoffFlagRef(specialty(" tablet:abc ")))
        assertEquals(HandoffFlagRef("checklist", "c-123"), handoffFlagRef(specialty(" checklist:c-123 ")))
        assertEquals(HandoffFlagRef("checklist", "c-123"), handoffFlagRef(specialty("checklist: c-123")))
    }

    @Test
    fun parsesTruckSetAtTolerantly() {
        val setAt = parseHandoffTruckSetAt(
            """
            {"itemFlagsSetAt":{
              "board_stock:5f0c":{"truck":"2026-10-08T14:00:00.123Z","count":"2026-10-08T15:00:00Z"},
              "checklist:c-1":{"count":"2026-10-08T15:00:00Z"},
              "checklist:c-2":{"truck":"yesterday"},
              "checklist:c-3":"2026-10-08T14:00:00Z",
              "checklist:c-4":{"truck":12345},
              "nocolon":{"truck":"2026-10-08T14:00:00Z"}
            }}
            """.trimIndent()
        )
        assertEquals(mapOf(HandoffFlagRef("board_stock", "5f0c") to java.time.Instant.parse("2026-10-08T14:00:00.123Z")), setAt)
        assertTrue(parseHandoffTruckSetAt("{corrupt").isEmpty())
        assertTrue(parseHandoffTruckSetAt("""{"itemFlagsSetAt":[1]}""").isEmpty())
        assertTrue(parseHandoffTruckSetAt("""{"itemFlags":{}}""").isEmpty())
    }

    @Test
    fun keySplitsOnFirstColonOnly() {
        assertEquals(HandoffFlagRef("specialty_items", "tablet:9a1e"), HandoffFlagRef.fromKey("specialty_items:tablet:9a1e"))
        assertEquals("specialty_items:default:0:bumps", HandoffFlagRef("specialty_items", "default:0:bumps").key)
        assertNull(HandoffFlagRef.fromKey("nocolon"))
        assertNull(HandoffFlagRef.fromKey(":id"))
        assertNull(HandoffFlagRef.fromKey("source:"))
    }

    @Test
    fun parsesFlagsAndTreatsUnknownAsAuto() {
        val flags = parseHandoffItemFlags(
            """
            {"schemaVersion":1,"itemFlags":{
              "board_stock:5f0c":{"truck":"loose"},
              "specialty_items:tablet:9a1e":{"truck":"later","count":"line"},
              "specialty_items:default:0:bumps":{"count":"qty"},
              "checklist:c-123":{"truck":"LOOSE","count":"qty"},
              "checklist:c-9":{"truck":"teleport","future":"x"},
              "nocolon":{"truck":"shop"},
              "board_stock:bad":"not-an-object"
            },"itemFlagsSetAt":{"board_stock:5f0c":{"truck":"2026-10-08T14:00:00.123Z"}},"status":"ok"}
            """.trimIndent()
        )
        assertEquals(HandoffItemFlags(truck = HandoffTruck.LOOSE), flags[HandoffFlagRef("board_stock", "5f0c")])
        assertEquals(HandoffItemFlags(HandoffTruck.LATER, HandoffCount.LINE), flags[HandoffFlagRef("specialty_items", "tablet:9a1e")])
        assertEquals(HandoffItemFlags(count = HandoffCount.QTY), flags[HandoffFlagRef("specialty_items", "default:0:bumps")])
        // "LOOSE" is not a valid value: truck is Auto, count survives.
        assertEquals(HandoffItemFlags(count = HandoffCount.QTY), flags[HandoffFlagRef("checklist", "c-123")])
        // Entirely unknown => Auto => no entry.
        assertNull(flags[HandoffFlagRef("checklist", "c-9")])
        assertEquals(4, flags.size)
    }

    @Test
    fun missingCorruptOrConflictCopyMeansNoFlags() {
        val base = Files.createTempDirectory("handoff-flags").toFile()
        val job = "670 - ESCHRICH"
        assertTrue(loadHandoffItemFlags(base, job).isEmpty())
        val admin = File(base, "$job/.metadata/admin").apply { mkdirs() }
        File(admin, "handoff.sync-conflict-20261008-140000-ABCDEFG.json").writeText("""{"itemFlags":{"board_stock:x":{"truck":"loose"}}}""")
        assertTrue(loadHandoffItemFlags(base, job).isEmpty())
        File(admin, "handoff.json").writeText("{corrupt")
        assertTrue(loadHandoffItemFlags(base, job).isEmpty())
        File(admin, "handoff.json").writeText("""{"schemaVersion":1}""")
        assertTrue(loadHandoffItemFlags(base, job).isEmpty())
        File(admin, "handoff.json").writeText("""{"itemFlags":{"board_stock:x":{"truck":"shop"}}}""")
        assertEquals(HandoffTruck.SHOP, loadHandoffItemFlags(base, job)[HandoffFlagRef("board_stock", "x")]?.truck)
    }

    @Test
    fun conflictCopyWithDifferentFlagsIsNeverReadWhenHandoffJsonExists() {
        val base = Files.createTempDirectory("handoff-flags").toFile()
        val job = "670 - ESCHRICH"
        val admin = File(base, "$job/.metadata/admin").apply { mkdirs() }
        File(admin, "handoff.json").writeText(
            """{"itemFlags":{"board_stock:x":{"truck":"shop"}},"itemFlagsSetAt":{"board_stock:x":{"truck":"2026-10-08T14:00:00Z"}}}"""
        )
        File(admin, "handoff.sync-conflict-20261008-140000-ABCDEFG.json").writeText(
            """{"itemFlags":{"board_stock:x":{"truck":"loose"},"board_stock:y":{"truck":"later"}},"itemFlagsSetAt":{"board_stock:y":{"truck":"2030-01-01T00:00:00Z"}}}"""
        )
        val state = loadHandoffJsonState(base, job)
        assertEquals(mapOf(HandoffFlagRef("board_stock", "x") to HandoffItemFlags(HandoffTruck.SHOP)), state.flags)
        assertEquals(setOf(HandoffFlagRef("board_stock", "x")), state.truckSetAt.keys)
    }
}
