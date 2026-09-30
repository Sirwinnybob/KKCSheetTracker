package com.kkc.sheettracker.ui.specialty

import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_OTHER
import com.kkc.sheettracker.data.SpecialtyProgressStore
import com.kkc.sheettracker.data.resolveSheetRipTallyState
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.AdminBoardStockItem
import com.kkc.sheettracker.data.models.SpecialtyCompletionState
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemAttachment
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred

class SpecialtyJobDetailScreenLogicTest {
@Test
    fun specialtySheetRipLengthLabel_showsConfiguredRipLength() {
        assertEquals("2x 9ft Sheet Rips", specialtySheetRipLengthLabel(2, 9))
        assertEquals("3x 12ft Sheet Rips", specialtySheetRipLengthLabel(3, 12))
    }

    @Test
    fun specialtySheetRipItemLabel_identifiesPlywoodCrown() {
        val crown = AdminBoardStockItem(
            id = "crown",
            material = "Maple Plywood",
            name = "Crown 151",
            feet = 18.0,
            mode = "sheet",
            type = "crown"
        )

        assertEquals("Plywood Crown — Crown 151", specialtySheetRipItemLabel(crown))
    }

    @Test
    fun specialtySheetRipItemLabel_keepsNonCrownName() {
        val sheetItem = AdminBoardStockItem(
            id = "panel",
            material = "Maple Plywood",
            name = "Toe Kick",
            feet = 18.0,
            mode = "sheet",
            type = "panel"
        )

        assertEquals("Toe Kick", specialtySheetRipItemLabel(sheetItem))
    }

    @Test
    fun sheetRipTallyCompletion_requiresEveryConfiguredRipDespiteLegacyCompletion() {
        assertTrue(resolveSheetRipTallyState(2, false, 2).isComplete)
        assertFalse(resolveSheetRipTallyState(1, true, 2).isComplete)
    }

    @Test
    fun checklistTogglesForItem_customMultiStation_createsOneStationLabeledTogglePerStation() {
        val resolved = SpecialtyResolvedItem(
            item = SpecialtyItem(
                id = "custom-1",
                name = "Custom Item",
                category = SpecialtyItemCategory.CUSTOM,
                stations = listOf(SpecialtyStation.CNC, SpecialtyStation.EDGE_BANDER, SpecialtyStation.ASSEMBLY)
            ),
            completionByKey = mapOf(
                SpecialtyStation.CNC.name to SpecialtyCompletionState(completed = true),
                SpecialtyStation.EDGE_BANDER.name to SpecialtyCompletionState(completed = false),
                SpecialtyStation.ASSEMBLY.name to SpecialtyCompletionState(completed = false)
            )
        )

        val toggles = checklistTogglesForItem(resolved, completionOverrides = emptyMap())

        assertEquals(3, toggles.size)
        assertEquals(listOf("CNC", "EDGE_BANDER", "ASSEMBLY"), toggles.map { it.completionKey })
        assertEquals(listOf("CNC", "EDGE BANDER", "ASSEMBLY"), toggles.map { it.label })
    }

    @Test
    fun checklistTogglesForItem_toOrder_createsSingleItemCompletionToggle() {
        val resolved = SpecialtyResolvedItem(
            item = SpecialtyItem(
                id = "order-1",
                name = "Ordered Part",
                category = SpecialtyItemCategory.TO_ORDER,
                stations = listOf(SpecialtyStation.CNC, SpecialtyStation.SAW)
            ),
            completionByKey = mapOf(
                SpecialtyProgressStore.ITEM_COMPLETION_KEY to SpecialtyCompletionState(completed = true)
            )
        )

        val toggles = checklistTogglesForItem(resolved, completionOverrides = emptyMap())

        assertEquals(1, toggles.size)
        assertEquals(SpecialtyProgressStore.ITEM_COMPLETION_KEY, toggles.first().completionKey)
        assertNull(toggles.first().label)
        assertTrue(toggles.first().checked)
    }

    @Test
    fun isChecklistItemComplete_customMultiStation_requiresAllStationsChecked() {
        val resolved = SpecialtyResolvedItem(
            item = SpecialtyItem(
                id = "custom-2",
                name = "Custom Item",
                category = SpecialtyItemCategory.CUSTOM,
                stations = listOf(SpecialtyStation.CNC, SpecialtyStation.SAW)
            ),
            completionByKey = mapOf(
                SpecialtyStation.CNC.name to SpecialtyCompletionState(completed = true),
                SpecialtyStation.SAW.name to SpecialtyCompletionState(completed = false)
            )
        )

        assertFalse(isChecklistItemComplete(resolved, completionOverrides = emptyMap()))

        val overrides = mapOf("custom-2::SAW" to true)
        assertTrue(isChecklistItemComplete(resolved, completionOverrides = overrides))
    }

    @Test
    fun checklistOverrides_concurrentSaves_stayUntilALaterLoadSettlesThem() = runBlocking {
        val overrides = ChecklistOverrides()
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        val saveA = overrides.toggle(this, "a", true, write = { gateA.await() })
        overrides.toggle(this, "b", true, write = { gateB.await() })
        yield()
        assertFalse(isToggleEnabled("a", overrides.inFlight))
        assertFalse(isToggleEnabled("b", overrides.inFlight))

        val loadBeforeSaveReturned = overrides.startLoad()
        gateA.complete(Unit)
        saveA.join()
        assertTrue(isToggleEnabled("a", overrides.inFlight))
        assertFalse(isToggleEnabled("b", overrides.inFlight))
        assertEquals(1, overrides.reloadRequest)

        // A load that started before a's save returned can't clear it, even if it shows false.
        overrides.reconcile(loadBeforeSaveReturned, mapOf("a" to false, "b" to false))
        assertEquals(true, overrides.values["a"])

        // One that started after it can (the save wrote nothing, or lost); b is still saving.
        overrides.reconcile(overrides.startLoad(), mapOf("a" to false, "b" to false))
        assertNull(overrides.values["a"])
        assertEquals(true, overrides.values["b"])

        gateB.complete(Unit)
        yield()
        overrides.reconcile(overrides.startLoad(), mapOf("b" to true))
        assertTrue(overrides.values.isEmpty())
    }

    @Test
    fun checklistOverrides_failedSaveShowsStoredValueAgain() = runBlocking {
        val overrides = ChecklistOverrides()
        var errors = 0
        overrides.toggle(this, "a", true, write = { error("disk full") }, onError = { errors++ }).join()
        assertNull(overrides.values["a"])
        assertTrue(isToggleEnabled("a", overrides.inFlight))
        assertEquals(1, errors)
        assertEquals(1, overrides.reloadRequest)
    }

    @Test
    fun buildSpecialtyDetailSections_usesSavedStationOrder_andKeepsOtherLast() {
        val sections = buildSpecialtyDetailSections(
            resolvedItems = listOf(
                resolvedItem("other", emptyList()),
                resolvedItem("saw", listOf(SpecialtyStation.SAW)),
                resolvedItem("cnc", listOf(SpecialtyStation.CNC)),
                resolvedItem("multi", listOf(SpecialtyStation.CNC, SpecialtyStation.SAW)),
            ),
            stationOrder = listOf(SpecialtyStation.SAW, SpecialtyStation.CNC)
        )

        assertEquals(
            listOf(SpecialtyStation.SAW.name, SpecialtyStation.CNC.name, SPECIALTY_VIEWER_SECTION_ID_OTHER),
            sections.map { it.id }
        )
        assertEquals(listOf("saw", "multi"), sections[0].items.map { it.item.id })
        assertEquals(listOf("cnc", "multi"), sections[1].items.map { it.item.id })
        assertEquals(listOf("other"), sections[2].items.map { it.item.id })
    }

    @Test
    fun specialtySheetRipLazyRowEntries_keepEveryLargeGroupRowAsItsOwnListEntry() {
        val sheetRips = (1..95).map { index ->
            AdminBoardStockItem(
                id = "sheet-rip-$index",
                material = "Material $index",
                name = "Rip $index",
                feet = 10.0,
                mode = "sheet"
            )
        }

        val entries = specialtySheetRipLazyRowEntries(sheetRips)

        assertEquals(95, entries.size)
        assertEquals(95, entries.map { it.key }.toSet().size)
        assertEquals(sheetRips, entries.map { it.item })
    }

    @Test
    fun specialtySheetRipItems_includesOnlySheetModeCrown() {
        val sheetCrown = AdminBoardStockItem("sheet", "Maple", "Crown", 18.0, mode = "sheet", type = "crown")
        val boardCrown = AdminBoardStockItem("board", "Maple", "Crown", 18.0, mode = "bd_ft", type = "crown")

        assertEquals(listOf(sheetCrown), specialtySheetRipItems(listOf(sheetCrown, boardCrown)))
    }

    @Test
    fun specialtyChecklistLazyRowEntries_keepEveryLargeStationRowAsItsOwnListEntry() {
        val checklistItems = (1..95).map { index ->
            resolvedItem("cnc-$index", listOf(SpecialtyStation.CNC))
        }

        val entries = specialtyChecklistLazyRowEntries(
            sectionId = SpecialtyStation.CNC.name,
            items = checklistItems
        )

        assertEquals(95, entries.size)
        assertEquals(95, entries.map { it.key }.toSet().size)
        assertEquals(checklistItems, entries.map { it.item })
    }

    @Test
    fun toggleSpecialtySection_onlyAffectsCurrentExpandedSet() {
        val initial = linkedSetOf(SpecialtyStation.CNC.name, SpecialtyStation.SAW.name)

        val collapsed = toggleSpecialtySection(initial, SpecialtyStation.SAW.name)
        assertEquals(setOf(SpecialtyStation.CNC.name), collapsed)

        val expanded = toggleSpecialtySection(collapsed, SpecialtyStation.ASSEMBLY.name)
        assertEquals(
            linkedSetOf(SpecialtyStation.CNC.name, SpecialtyStation.ASSEMBLY.name),
            expanded
        )
    }

    @Test
    fun orderSpecialtyStations_appliesSavedChipOrder_andDeduplicates() {
        val ordered = orderSpecialtyStations(
            stations = listOf(
                SpecialtyStation.CNC,
                SpecialtyStation.SAW,
                SpecialtyStation.CNC,
                SpecialtyStation.ASSEMBLY
            ),
            stationOrder = listOf(SpecialtyStation.SAW, SpecialtyStation.ASSEMBLY)
        )

        assertEquals(
            listOf(SpecialtyStation.SAW, SpecialtyStation.ASSEMBLY, SpecialtyStation.CNC),
            ordered
        )
    }

    @Test
    fun hasClosetRodCutList_requiresClosetRodRows() {
        val index = HardwoodCutlistIndex(
            documents = listOf(
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.DOOR_CUT_LIST,
                    rows = listOf(HardwoodCutlistRow(rowId = "door-1"))
                ),
                HardwoodDocumentIndex(
                    docType = HardwoodDocType.CLOSET_ROD_CUT_LIST,
                    rows = listOf(HardwoodCutlistRow(rowId = "rod-1", length = "36", unitType = "PER_FT"))
                )
            )
        )

        assertTrue(hasClosetRodCutList(index))
        assertFalse(
            hasClosetRodCutList(
                HardwoodCutlistIndex(
                    documents = listOf(
                        HardwoodDocumentIndex(docType = HardwoodDocType.CLOSET_ROD_CUT_LIST)
                    )
                )
            )
        )
        assertFalse(hasClosetRodCutList(null))
    }

    private fun resolvedItem(
        id: String,
        stations: List<SpecialtyStation>,
        category: SpecialtyItemCategory = SpecialtyItemCategory.CUSTOM
    ): SpecialtyResolvedItem {
        return SpecialtyResolvedItem(
            item = SpecialtyItem(
                id = id,
                name = "Item $id",
                category = category,
                stations = stations
            ),
            completionByKey = mapOf(
                SpecialtyProgressStore.ITEM_COMPLETION_KEY to SpecialtyCompletionState(completed = false)
            ),
            isComplete = false
        )
    }

    private fun checklistItem(id: String, sawDone: Boolean) = SpecialtyResolvedItem(
        item = SpecialtyItem(id = id, name = id, category = SpecialtyItemCategory.CUSTOM, stations = listOf(SpecialtyStation.SAW)),
        completionByKey = mapOf("SAW" to SpecialtyCompletionState(completed = sawDone))
    )

    @Test
    fun checklistOverride_keptWhileSaveInFlight_evenWhenOlderReloadsLand() {
        // Tick is pending (save not returned): a reload that doesn't show it must not clear it.
        assertFalse(shouldDropChecklistOverride(override = true, stored = false, savedAfterLoad = null, landedLoad = 7))
    }

    @Test
    fun checklistOverride_droppedOnceStoredMatches() {
        assertTrue(shouldDropChecklistOverride(override = true, stored = true, savedAfterLoad = null, landedLoad = 3))
    }

    @Test
    fun checklistOverride_reloadStartedBeforeSaveReturned_doesNotDropIt() {
        // Save returned after load 4 had started; load 4 may predate the write, so keep the tick.
        assertFalse(shouldDropChecklistOverride(override = true, stored = false, savedAfterLoad = 4, landedLoad = 4))
    }

    @Test
    fun checklistOverride_reloadStartedAfterSave_showsStoredValueEvenIfUnchanged() {
        // Read-only store (archive / view-only) or a lost merge: nothing changed, but a reload
        // that began after the save still has the last word, so the box snaps back.
        assertTrue(shouldDropChecklistOverride(override = true, stored = false, savedAfterLoad = 4, landedLoad = 5))
    }

    @Test
    fun storedChecklistValues_ignoreOverrides() {
        val item = checklistItem("a", sawDone = false)
        val stored = storedChecklistValues(listOf(item))
        assertEquals(listOf(false), stored.values.toList())
        assertEquals(checklistTogglesForItem(item, emptyMap()).single().controlId, stored.keys.single())
    }

    @Test
    fun reuseUnchangedResolvedItems_keepsInstancesForEqualItems() {
        val a = checklistItem("a", sawDone = false)
        val b = checklistItem("b", sawDone = false)
        val previous = listOf(a, b)

        val same = reuseUnchangedResolvedItems(previous, listOf(checklistItem("a", false), checklistItem("b", false)))
        assertTrue(same === previous)

        val changed = reuseUnchangedResolvedItems(previous, listOf(checklistItem("a", false), checklistItem("b", true)))
        assertTrue(changed[0] === a)
        assertFalse(changed[1] === b)
        assertTrue(changed[1].completionByKey.getValue("SAW").completed)

        val fresh = listOf(checklistItem("a", false))
        assertTrue(reuseUnchangedResolvedItems(emptyList(), fresh) === fresh)
        assertEquals(listOf("a"), reuseUnchangedResolvedItems(previous, fresh).map { it.item.id })
    }

    @Test
    fun quantityFormat_trimsFloatNoiseAndTrailingZeros() {
        assertEquals("51.0425", formatSpecialtyQuantity(51.042500000000004))
        assertEquals("2", formatSpecialtyQuantity(2.0))
        assertEquals("0.5", formatSpecialtyQuantity(0.5))
        assertEquals("1.2346", formatSpecialtyQuantity(1.23456))
        assertEquals("120", formatSpecialtyQuantity(120.0))
    }

    @Test
    fun quantityFormat_nonFiniteDoesNotThrow() {
        assertEquals("NaN", formatSpecialtyQuantity(Double.NaN))
        assertEquals("Infinity", formatSpecialtyQuantity(Double.POSITIVE_INFINITY))
        assertEquals("-Infinity", formatSpecialtyQuantity(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun editedQuantity_untouchedFieldKeepsExactStoredValue() {
        // The field shows 0.3333; saving without touching it must not truncate the stored value.
        assertEquals(0.333333, editedSpecialtyQuantity(0.333333, "0.3333")!!, 0.0)
        assertEquals(0.333333, editedSpecialtyQuantity(0.333333, " 0.3333 ")!!, 0.0)
        assertEquals(0.5, editedSpecialtyQuantity(0.333333, "0.5")!!, 0.0)
        assertEquals(3.0, editedSpecialtyQuantity(null, "3")!!, 0.0)
        assertNull(editedSpecialtyQuantity(2.0, ""))
        assertNull(editedSpecialtyQuantity(2.0, "abc"))
        assertNull(editedSpecialtyQuantity(null, "NaN"))
        assertNull(editedSpecialtyQuantity(null, "1e999"))
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private fun adminFile(kind: String, itemId: String, name: String): File =
        File(tmp.root, "Job 1/.metadata/admin/$kind/$itemId/$name").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }

    @Test
    fun resolveAttachment_specialtyItem_readsSpecialtyAttachmentsFolder() {
        val expected = adminFile("specialty_attachments", "spec-1", "att-1.pdf")
        adminFile("checklist_attachments", "spec-1", "att-1.pdf")
        val att = SpecialtyItemAttachment(id = "att-1", filename = "att-1.pdf", originalName = "Drawing.pdf")

        assertEquals(expected, resolveSpecialtyAttachmentFile(tmp.root.path, "Job 1", "spec-1", att))
    }

    @Test
    fun resolveAttachment_checklistItem_readsChecklistAttachmentsFolder() {
        val expected = adminFile("checklist_attachments", "chk-1", "att-2.png")
        val att = SpecialtyItemAttachment(id = "att-2", filename = "att-2.png", originalName = "Photo.png")

        assertEquals(expected, resolveSpecialtyAttachmentFile(tmp.root.path, "Job 1", "checklist:chk-1", att))
    }

    @Test
    fun resolveAttachment_fallsBackToFileContainingAttachmentId() {
        val expected = adminFile("specialty_attachments", "spec-2", "spec-2_att-3.pdf")
        val att = SpecialtyItemAttachment(id = "att-3", filename = "att-3.pdf", originalName = "Spec.pdf")

        assertEquals(expected, resolveSpecialtyAttachmentFile(tmp.root.path, "Job 1", "spec-2", att))
    }

    @Test
    fun resolveAttachment_missingFileOrBlankId_returnsNull() {
        adminFile("specialty_attachments", "spec-3", "other.pdf")

        assertNull(
            resolveSpecialtyAttachmentFile(
                tmp.root.path, "Job 1", "spec-3",
                SpecialtyItemAttachment(id = "att-4", filename = "att-4.pdf", originalName = "Gone.pdf")
            )
        )
        assertNull(
            resolveSpecialtyAttachmentFile(
                tmp.root.path, "Job 1", "spec-3",
                SpecialtyItemAttachment(id = "", filename = "missing.pdf", originalName = "Blank.pdf")
            )
        )
    }
}
