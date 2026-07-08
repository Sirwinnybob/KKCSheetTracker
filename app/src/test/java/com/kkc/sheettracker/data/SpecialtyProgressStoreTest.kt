package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.SpecialtyStation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SpecialtyProgressStoreTest {
    private val jobFolderName = "1234 - Test Job"
    private val tabletId = "tablet-local"

    @Test
    fun parsesV1CompletionObjectAndNullCompatibility() {
        val baseDir = createTempBaseDir()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "item-custom",
                      "name": "Custom Multi",
                      "cabinetNumbers": ["C1"],
                      "category": "CUSTOM",
                      "stations": ["CNC", "SAW"]
                    },
                    {
                      "id": "item-order",
                      "name": "To Order Item",
                      "cabinetNumbers": [],
                      "category": "TO_ORDER",
                      "stations": []
                    }
                  ]
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-a",
            body = """
                {
                  "tabletId": "tablet-a",
                  "schemaVersion": 1,
                  "completions": {
                    "item-custom": {
                      "completedAt": "2026-05-01T00:00:01Z",
                      "completedBy": "tablet-a"
                    },
                    "item-order": null
                  }
                }
            """.trimIndent()
        )

        val store = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
        val resolved = store.loadResolvedItems(jobFolderName)

        val custom = resolved.first { it.item.id == "item-custom" }
        val order = resolved.first { it.item.id == "item-order" }

        assertTrue(custom.isComplete)
        assertTrue(custom.completionByKey[SpecialtyStation.CNC.name]?.completed == true)
        assertTrue(custom.completionByKey[SpecialtyStation.SAW.name]?.completed == true)
        assertFalse(order.isComplete)
    }

    @Test
    fun mergesV2StationSplitAcrossTrackerFiles() {
        val baseDir = createTempBaseDir()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "item-custom",
                      "name": "Custom Multi",
                      "cabinetNumbers": ["C1"],
                      "category": "CUSTOM",
                      "stations": ["CNC", "SAW", "ASSEMBLY"]
                    }
                  ]
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-a",
            body = """
                {
                  "tabletId": "tablet-a",
                  "schemaVersion": 2,
                  "completions": {
                    "item-custom": {
                      "stations": {
                        "CNC": {
                          "completed": true,
                          "completedAt": "2026-05-01T00:00:01Z",
                          "completedBy": "tablet-a"
                        },
                        "SAW": {
                          "completed": true,
                          "completedAt": "2026-05-01T00:00:02Z",
                          "completedBy": "tablet-a"
                        }
                      }
                    }
                  }
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-b",
            body = """
                {
                  "tabletId": "tablet-b",
                  "schemaVersion": 2,
                  "completions": {
                    "item-custom": {
                      "stations": {
                        "ASSEMBLY": {
                          "completed": true,
                          "completedAt": "2026-05-01T00:00:03Z",
                          "completedBy": "tablet-b"
                        }
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val store = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
        val custom = store.loadResolvedItems(jobFolderName).first()

        assertTrue(custom.isComplete)
        assertTrue(custom.completionByKey[SpecialtyStation.CNC.name]?.completed == true)
        assertTrue(custom.completionByKey[SpecialtyStation.SAW.name]?.completed == true)
        assertTrue(custom.completionByKey[SpecialtyStation.ASSEMBLY.name]?.completed == true)
    }

    @Test
    fun mergeUsesLatestCompletedAtPerCompletionKey() {
        val baseDir = createTempBaseDir()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "item-order",
                      "name": "To Order Item",
                      "cabinetNumbers": [],
                      "category": "TO_ORDER",
                      "stations": []
                    }
                  ]
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-a",
            body = """
                {
                  "tabletId": "tablet-a",
                  "schemaVersion": 2,
                  "completions": {
                    "item-order": {
                      "completion": {
                        "completed": true,
                        "completedAt": "2026-05-01T00:00:01Z",
                        "completedBy": "tablet-a"
                      }
                    }
                  }
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-b",
            body = """
                {
                  "tabletId": "tablet-b",
                  "schemaVersion": 2,
                  "completions": {
                    "item-order": {
                      "completion": {
                        "completed": false,
                        "completedAt": "2026-05-01T00:00:02Z",
                        "completedBy": "tablet-b"
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val store = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
        val order = store.loadResolvedItems(jobFolderName).first()

        assertFalse(order.isComplete)
        assertFalse(order.completionByKey[SpecialtyProgressStore.ITEM_COMPLETION_KEY]?.completed ?: true)
    }

    @Test
    fun aggregatesCompletionByCategoryAndStationCountRules() {
        val baseDir = createTempBaseDir()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "custom-multi",
                      "name": "Custom Multi",
                      "cabinetNumbers": ["C1"],
                      "category": "CUSTOM",
                      "stations": ["CNC", "SAW"]
                    },
                    {
                      "id": "custom-single",
                      "name": "Custom Single",
                      "cabinetNumbers": ["C2"],
                      "category": "CUSTOM",
                      "stations": ["EDGE_BANDER"]
                    },
                    {
                      "id": "to-order",
                      "name": "To Order",
                      "cabinetNumbers": ["C3"],
                      "category": "TO_ORDER",
                      "stations": ["CNC", "SAW", "ASSEMBLY"]
                    }
                  ]
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-a",
            body = """
                {
                  "tabletId": "tablet-a",
                  "schemaVersion": 2,
                  "completions": {
                    "custom-multi": {
                      "stations": {
                        "CNC": {
                          "completed": true,
                          "completedAt": "2026-05-01T00:00:01Z",
                          "completedBy": "tablet-a"
                        }
                      }
                    },
                    "custom-single": {
                      "completion": {
                        "completed": true,
                        "completedAt": "2026-05-01T00:00:02Z",
                        "completedBy": "tablet-a"
                      }
                    },
                    "to-order": {
                      "completion": {
                        "completed": true,
                        "completedAt": "2026-05-01T00:00:03Z",
                        "completedBy": "tablet-a"
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val store = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
        val byId = store.loadResolvedItems(jobFolderName).associateBy { it.item.id }

        assertFalse(byId.getValue("custom-multi").isComplete)
        assertTrue(byId.getValue("custom-single").isComplete)
        assertTrue(byId.getValue("to-order").isComplete)
    }

    @Test
    fun writesOnlyLocalTrackerFile() = runBlocking {
        val baseDir = createTempBaseDir()
        val originalItemsBody = """
            {
              "items": [
                {
                  "id": "item-order",
                  "name": "To Order Item",
                  "cabinetNumbers": [],
                  "category": "TO_ORDER",
                  "stations": []
                }
              ]
            }
        """.trimIndent()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = originalItemsBody
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-other",
            body = """
                {
                  "tabletId": "tablet-other",
                  "schemaVersion": 2,
                  "completions": {
                    "item-order": {
                      "completion": {
                        "completed": true,
                        "completedAt": "2026-05-01T00:00:01Z",
                        "completedBy": "tablet-other"
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val otherBefore = trackerFile(baseDir, jobFolderName, "tablet-other").readText()
        val itemsBefore = specialtyItemsFile(baseDir, jobFolderName).readText()

        val store = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
        store.setCompletion(
            jobFolderName = jobFolderName,
            itemId = "item-order",
            completionKey = SpecialtyProgressStore.ITEM_COMPLETION_KEY,
            completed = true,
            completedBy = tabletId,
            completedAt = "2026-05-02T00:00:00Z"
        )

        val localFile = trackerFile(baseDir, jobFolderName, tabletId)
        assertTrue(localFile.exists())
        assertTrue(localFile.readText().contains("\"tabletId\": \"$tabletId\""))
        assertTrue(localFile.readText().contains("\"schemaVersion\": 2"))

        assertTrue(otherBefore == trackerFile(baseDir, jobFolderName, "tablet-other").readText())
        assertTrue(itemsBefore == specialtyItemsFile(baseDir, jobFolderName).readText())
    }

    @Test
    fun parsesLegacyTruthyAndFalsyStringCompletionValues() {
        val baseDir = createTempBaseDir()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {"id":"f1","name":"f1","cabinetNumbers":[],"category":"TO_ORDER","stations":[]},
                    {"id":"f2","name":"f2","cabinetNumbers":[],"category":"TO_ORDER","stations":[]},
                    {"id":"f3","name":"f3","cabinetNumbers":[],"category":"TO_ORDER","stations":[]},
                    {"id":"f4","name":"f4","cabinetNumbers":[],"category":"TO_ORDER","stations":[]},
                    {"id":"t1","name":"t1","cabinetNumbers":[],"category":"TO_ORDER","stations":[]},
                    {"id":"t2","name":"t2","cabinetNumbers":[],"category":"TO_ORDER","stations":[]},
                    {"id":"t3","name":"t3","cabinetNumbers":[],"category":"TO_ORDER","stations":[]},
                    {"id":"t4","name":"t4","cabinetNumbers":[],"category":"TO_ORDER","stations":[]},
                    {"id":"u1","name":"u1","cabinetNumbers":[],"category":"TO_ORDER","stations":[]}
                  ]
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-a",
            body = """
                {
                  "tabletId":"tablet-a",
                  "schemaVersion":2,
                  "completions":{
                    "f1":{"completion":"false"},
                    "f2":{"completion":"0"},
                    "f3":{"completion":"no"},
                    "f4":{"completion":"off"},
                    "t1":{"completion":"true"},
                    "t2":{"completion":"1"},
                    "t3":{"completion":"yes"},
                    "t4":{"completion":"on"},
                    "u1":{"completion":"maybe"}
                  }
                }
            """.trimIndent()
        )

        val byId = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)
            .associateBy { it.item.id }

        assertFalse(byId.getValue("f1").isComplete)
        assertFalse(byId.getValue("f2").isComplete)
        assertFalse(byId.getValue("f3").isComplete)
        assertFalse(byId.getValue("f4").isComplete)
        assertTrue(byId.getValue("t1").isComplete)
        assertTrue(byId.getValue("t2").isComplete)
        assertTrue(byId.getValue("t3").isComplete)
        assertTrue(byId.getValue("t4").isComplete)
        assertFalse(byId.getValue("u1").isComplete)
    }

    @Test
    fun mergePrefersValidTimestampOverInvalidOrMissingTimestamps() {
        val baseDir = createTempBaseDir()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {"id":"item-order","name":"To Order","cabinetNumbers":[],"category":"TO_ORDER","stations":[]}
                  ]
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-a",
            body = """
                {
                  "tabletId":"tablet-a",
                  "schemaVersion":2,
                  "completions":{
                    "item-order":{
                      "completion":{
                        "completed":false,
                        "completedAt":"1970-01-01T00:00:00Z",
                        "completedBy":"tablet-a"
                      }
                    }
                  }
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-z",
            body = """
                {
                  "tabletId":"tablet-z",
                  "schemaVersion":2,
                  "completions":{
                    "item-order":{
                      "completion":{
                        "completed":true,
                        "completedAt":"not-a-time",
                        "completedBy":"tablet-z"
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val resolved = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)
            .first()

        assertFalse(resolved.isComplete)
        val completion = resolved.completionByKey.getValue(SpecialtyProgressStore.ITEM_COMPLETION_KEY)
        assertFalse(completion.completed)
        assertTrue(completion.completedAt == "1970-01-01T00:00:00Z")
    }

    @Test
    fun loadResolvedItems_missingSpecialtyItemsFile_returnsEmptyWithoutCrash() {
        val baseDir = createTempBaseDir()
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-a",
            body = """
                {
                  "tabletId":"tablet-a",
                  "schemaVersion":2,
                  "completions": {
                    "item-1": {
                      "completion": {
                        "completed": true,
                        "completedAt": "2026-05-01T00:00:00Z"
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val resolved = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun invalidateFromTrackerFile_invalidatesMatchingJobCacheAndBumpsVersion() {
        val baseDir = createTempBaseDir()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {"id":"item-order","name":"To Order","cabinetNumbers":[],"category":"TO_ORDER","stations":[]}
                  ]
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-a",
            body = """
                {
                  "tabletId":"tablet-a",
                  "schemaVersion":2,
                  "completions": {
                    "item-order": {
                      "completion": {
                        "completed": false,
                        "completedAt": "2026-05-01T00:00:00Z"
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val store = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
        store.loadResolvedItems(jobFolderName)
        val versionBefore = store.progressVersion.value

        val recognized = store.invalidateFromTrackerFile(
            File(baseDir, "$jobFolderName/.metadata/admin/.tracker/tablet-a.json")
        )

        assertTrue(recognized)
        assertEquals(versionBefore + 1L, store.progressVersion.value)

        val unrelated = store.invalidateFromTrackerFile(
            File(baseDir, "$jobFolderName/.metadata/admin/specialty_items.json")
        )
        assertFalse(unrelated)
    }

    @Test
    fun parsesAdminV2AliasesAndObjectAttachmentsAndNumericCabinets() {
        val baseDir = createTempBaseDir()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "item-a",
                      "name": "Alias Item",
                      "cabinetNumbers": [101, "102", 103],
                      "category": "TO_ORDER",
                      "stations": [],
                      "modelNumber": "M-123",
                      "trackingNumber": "TRK-9",
                      "attachments": [
                        "legacy.pdf",
                        { "id": "att-1", "filename": "f1.pdf", "originalName": "Spec Sheet.pdf" }
                      ]
                    }
                  ]
                }
            """.trimIndent()
        )

        val item = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)
            .first()
            .item

        assertEquals(listOf("101", "102", "103"), item.cabinetNumbers)
        assertEquals("M-123", item.model)
        assertEquals("TRK-9", item.tracking)
        assertTrue(item.attachments.any { it.filename == "legacy.pdf" || it.originalName == "legacy.pdf" })
        assertTrue(item.attachments.any { it.filename == "f1.pdf" || it.originalName == "Spec Sheet.pdf" })
    }

    @Test
    fun includesChecklistSpecialtyItemsWhenModesContainProductionModesOrAreMissing() {
        val baseDir = createTempBaseDir()
        writeChecklistItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "c1",
                      "text": "Spec mode task",
                      "modes": ["SPECIALTY"],
                      "cabinetNumbers": [201],
                      "category": "CUSTOM"
                    },
                    {
                      "id": "c2",
                      "text": "Legacy no modes specialty task",
                      "cabinetNumbers": [202],
                      "category": "CUSTOM",
                      "stations": ["SPECIALTY"]
                    },
                    {
                      "id": "c3",
                      "text": "CNC task",
                      "modes": ["CNC"],
                      "cabinetNumbers": [203]
                    },
                    {
                      "id": "c4",
                      "text": "Delivery only task",
                      "modes": ["DELIVERY"],
                      "stations": ["DELIVERY"],
                      "cabinetNumbers": [204]
                    },
                    {
                      "id": "c5",
                      "text": "Delivery hardwoods task",
                      "modes": ["DELIVERY", "HARDWOODS"],
                      "stations": ["DELIVERY", "HARDWOODS"],
                      "cabinetNumbers": [205]
                    },
                    {
                      "id": "c6",
                      "text": "Assembly task",
                      "modes": ["ASSEMBLY"],
                      "cabinetNumbers": [206]
                    },
                    {
                      "id": "c7",
                      "text": "Hardwoods task",
                      "modes": ["HARDWOODS"],
                      "cabinetNumbers": [207]
                    },
                    {
                      "id": "c8",
                      "text": "Plain checklist task",
                      "cabinetNumbers": [208]
                    }
                  ]
                }
            """.trimIndent()
        )

        val byId = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)
            .associateBy { it.item.id }

        assertTrue(byId.containsKey("checklist:c1"))
        assertTrue(byId.containsKey("checklist:c2"))
        assertTrue(byId.containsKey("checklist:c3"))
        assertTrue(byId.containsKey("checklist:c4"))
        assertTrue(byId.containsKey("checklist:c5"))
        assertTrue(byId.containsKey("checklist:c6"))
        assertTrue(byId.containsKey("checklist:c7"))
        assertFalse(byId.containsKey("checklist:c8"))
        assertEquals(listOf(SpecialtyStation.SPECIALTY), byId.getValue("checklist:c2").item.stations)
        assertEquals(listOf(SpecialtyStation.CNC), byId.getValue("checklist:c3").item.stations)
        assertEquals(listOf(SpecialtyStation.ASSEMBLY), byId.getValue("checklist:c6").item.stations)
        assertEquals(listOf(SpecialtyStation.HARDWOODS), byId.getValue("checklist:c7").item.stations)
    }

    @Test
    fun trackerParsesDeviceIdAliasForAdminCompatibility() {
        val baseDir = createTempBaseDir()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {"id":"item-order","name":"To Order","cabinetNumbers":[],"category":"TO_ORDER","stations":[]}
                  ]
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-a",
            body = """
                {
                  "deviceId":"admin-pc",
                  "schemaVersion":1,
                  "completions":{
                    "item-order":{
                      "completion":{
                        "completed":true,
                        "completedAt":"2026-05-11T00:00:00Z",
                        "completedBy":"admin-pc"
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val resolved = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)
            .first()

        assertTrue(resolved.isComplete)
    }

    @Test
    fun parsesAutomationKeyFromSpecialtyItems() {
        val baseDir = createTempBaseDir()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id":"door-panels",
                      "name":"Door panels - 1/4 2s Hickory Rustic",
                      "category":"CUSTOM",
                      "stations":["SAW"],
                      "material":"1/4 2s Hickory Rustic",
                      "autoDetected":true,
                      "automationKey":"door_panels_auto|1/4 2S HICKORY RUSTIC|flat"
                    }
                  ]
                }
            """.trimIndent()
        )

        val item = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)
            .first()
            .item

        assertEquals("door_panels_auto|1/4 2S HICKORY RUSTIC|flat", item.automationKey)
        assertEquals("1/4 2s Hickory Rustic", item.material)
    }

    @Test
    fun suppressesAutoDetectedSawDoorPanelItemWhenCncOwnsMappedMaterial() {
        val baseDir = createTempBaseDir()
        writeMaterialMappings(baseDir, """{"1/4 MDF":"1_4 MDF"}""")
        writeDoorCutIndex(
            baseDir = baseDir,
            body = """
                {
                  "documents": [
                    {
                      "docType": "DOOR_CUT_LIST",
                      "pdfFilename": "1234 - Door Cut List.pdf",
                      "rows": [
                        {
                          "rowId": "DOOR_CUT_LIST:1:0:a",
                          "qty": 1,
                          "material": "1/4 MDF",
                          "description": "Center Panel",
                          "width": "10",
                          "length": "20",
                          "cabinets": ["22"],
                          "unitType": "SHEETS"
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()
        )
        writeCncMaterialMetadata(
            baseDir = baseDir,
            materialName = "1_4 MDF",
            partsBody = """
                [
                  {
                    "number": 1,
                    "width": 10.0,
                    "length": 20.0,
                    "name": "Center Panel",
                    "cabNumber": 22,
                    "room": "Kitchen"
                  }
                ]
            """.trimIndent()
        )
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id":"door-panels",
                      "name":"Door panels - 1/4 MDF",
                      "category":"CUSTOM",
                      "stations":["SAW"],
                      "material":"1/4 MDF",
                      "autoDetected":true,
                      "automationKey":"door_panels_auto|1/4 MDF|flat"
                    }
                  ]
                }
            """.trimIndent()
        )

        val resolved = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun doesNotSuppressManualSawItemWithSameMaterial() {
        val baseDir = createTempBaseDir()
        writeMaterialMappings(baseDir, """{"1/4 MDF":"1_4 MDF"}""")
        writeDoorCutIndex(
            baseDir = baseDir,
            body = """
                {
                  "documents": [
                    {
                      "docType": "DOOR_CUT_LIST",
                      "pdfFilename": "1234 - Door Cut List.pdf",
                      "rows": [
                        {
                          "rowId": "DOOR_CUT_LIST:1:0:a",
                          "qty": 1,
                          "material": "1/4 MDF",
                          "description": "Center Panel",
                          "width": "10",
                          "length": "20",
                          "cabinets": ["22"],
                          "unitType": "SHEETS"
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()
        )
        writeCncMaterialMetadata(
            baseDir = baseDir,
            materialName = "1_4 MDF",
            partsBody = """
                [
                  {
                    "number": 1,
                    "width": 10.0,
                    "length": 20.0,
                    "name": "Center Panel",
                    "cabNumber": 22,
                    "room": "Kitchen"
                  }
                ]
            """.trimIndent()
        )
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id":"manual-saw",
                      "name":"Door panels - 1/4 MDF",
                      "category":"CUSTOM",
                      "stations":["SAW"],
                      "material":"1/4 MDF",
                      "autoDetected":false
                    }
                  ]
                }
            """.trimIndent()
        )

        val resolved = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)

        assertEquals(listOf("manual-saw"), resolved.map { it.item.id })
    }

    @Test
    fun doesNotSuppressNonDoorPanelAutoDetectedItem() {
        val baseDir = createTempBaseDir()
        writeMaterialMappings(baseDir, """{"1/4 MDF":"1_4 MDF"}""")
        writeDoorCutIndex(
            baseDir = baseDir,
            body = """
                {
                  "documents": [
                    {
                      "docType": "DOOR_CUT_LIST",
                      "pdfFilename": "1234 - Door Cut List.pdf",
                      "rows": [
                        {
                          "rowId": "DOOR_CUT_LIST:1:0:a",
                          "qty": 1,
                          "material": "1/4 MDF",
                          "description": "Center Panel",
                          "width": "10",
                          "length": "20",
                          "cabinets": ["22"],
                          "unitType": "SHEETS"
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()
        )
        writeCncMaterialMetadata(
            baseDir = baseDir,
            materialName = "1_4 MDF",
            partsBody = """
                [
                  {
                    "number": 1,
                    "width": 10.0,
                    "length": 20.0,
                    "name": "Center Panel",
                    "cabNumber": 22,
                    "room": "Kitchen"
                  }
                ]
            """.trimIndent()
        )
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id":"auto-other",
                      "name":"Other auto item",
                      "category":"CUSTOM",
                      "stations":["SAW"],
                      "material":"1/4 MDF",
                      "autoDetected":true,
                      "automationKey":"different_rule|1/4 MDF"
                    }
                  ]
                }
            """.trimIndent()
        )

        val resolved = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)

        assertEquals(listOf("auto-other"), resolved.map { it.item.id })
    }

    @Test
    fun checklistCompletionSeedMergesWithTrackerAndNewestWins() {
        val baseDir = createTempBaseDir()
        writeChecklistItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "c1",
                      "text": "Checklist item",
                      "modes": ["SPECIALTY"],
                      "completedAt": "2026-05-11T00:00:00Z",
                      "completedBy": "admin"
                    }
                  ]
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-a",
            body = """
                {
                  "tabletId":"tablet-a",
                  "schemaVersion":2,
                  "completions":{
                    "checklist:c1":{
                      "completion":{
                        "completed":false,
                        "completedAt":"2026-05-12T00:00:00Z",
                        "completedBy":"tablet-a"
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val resolved = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)
            .first { it.item.id == "checklist:c1" }

        assertFalse(resolved.isComplete)
    }

    @Test
    fun checklistCompletionSeedAcceptsExplicitCompletedBooleanWithoutTimestamp() {
        val baseDir = createTempBaseDir()
        writeChecklistItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "c1",
                      "text": "Completed checklist item",
                      "modes": ["SPECIALTY"],
                      "completed": true,
                      "completedBy": "admin"
                    }
                  ]
                }
            """.trimIndent()
        )

        val resolved = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)
            .first { it.item.id == "checklist:c1" }

        assertTrue(resolved.isComplete)
        assertTrue(resolved.completionByKey.getValue(SpecialtyProgressStore.ITEM_COMPLETION_KEY).completed)
    }

    @Test
    fun checklistModesDriveDivisionCheckboxCount() {
        val baseDir = createTempBaseDir()
        writeChecklistItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "c1",
                      "text": "Division tagged item",
                      "modes": ["SPECIALTY", "CNC", "ASSEMBLY"],
                      "category": "CUSTOM"
                    }
                  ]
                }
            """.trimIndent()
        )

        val resolved = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .loadResolvedItems(jobFolderName)
            .first { it.item.id == "checklist:c1" }

        val keys = completionKeysForItem(resolved.item)
        assertEquals(3, keys.size)
        assertTrue(keys.contains("SPECIALTY"))
        assertTrue(keys.contains("CNC"))
        assertTrue(keys.contains("ASSEMBLY"))
    }

    @Test
    fun updateSpecialtyItemFieldsUpdatesChecklistBackedItems() = runBlocking {
        val baseDir = createTempBaseDir()
        writeChecklistItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "c1",
                      "text": "Checklist item",
                      "modes": ["SPECIALTY"],
                      "category": "CUSTOM"
                    }
                  ]
                }
            """.trimIndent()
        )

        SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .updateSpecialtyItemFields(
                jobFolderName = jobFolderName,
                itemId = "checklist:c1",
                dimensions = "12 x 24",
                quantity = 3.0,
                material = "Maple"
            )

        val updated = checklistItemsFile(baseDir, jobFolderName).readText()
        assertTrue(updated.contains("\"dimensions\": \"12 x 24\""))
        assertTrue(updated.contains("\"quantity\": 3.0"))
        assertTrue(updated.contains("\"material\": \"Maple\""))
    }

    @Test
    fun updateToOrderItemUpdatesChecklistBackedItemsWithHoursTrackerAliases() = runBlocking {
        val baseDir = createTempBaseDir()
        writeChecklistItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "c1",
                      "text": "Old name",
                      "modes": ["DELIVERY"],
                      "stations": ["DELIVERY"],
                      "category": "TO_ORDER",
                      "cabinetNumbers": [1]
                    }
                  ]
                }
            """.trimIndent()
        )

        SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
            .updateToOrderItem(
                jobFolderName = jobFolderName,
                itemId = "checklist:c1",
                name = "New name",
                cabinetNumbers = listOf("10", "11"),
                supplier = "Supplier A",
                model = "MODEL-1",
                orderDate = "07-08",
                tracking = "TRACK-1",
                orderUrl = "https://example.test/order",
                notes = "Bring to delivery",
                quantity = 12.0,
                material = "Brass",
                dimensions = "1 x 2"
            )

        val updated = checklistItemsFile(baseDir, jobFolderName).readText()
        assertTrue(updated.contains("\"text\": \"New name\""))
        assertFalse(updated.contains("\"name\": \"New name\""))
        assertTrue(updated.contains("\"modelNumber\": \"MODEL-1\""))
        assertTrue(updated.contains("\"trackingNumber\": \"TRACK-1\""))
        assertTrue(updated.contains("\"orderDate\": \"07-08\""))
        assertTrue(updated.contains("\"quantity\": 12.0"))
        assertTrue(updated.contains("\"material\": \"Brass\""))
        assertTrue(updated.contains("\"dimensions\": \"1 x 2\""))
    }

    private fun createTempBaseDir(): File = Files.createTempDirectory("specialty-progress-store-test").toFile()

    private fun writeSpecialtyItems(baseDir: File, jobFolderName: String, body: String) {
        val file = specialtyItemsFile(baseDir, jobFolderName)
        file.parentFile?.mkdirs()
        file.writeText(body)
    }

    private fun writeTrackerFile(baseDir: File, jobFolderName: String, tabletId: String, body: String) {
        val file = trackerFile(baseDir, jobFolderName, tabletId)
        file.parentFile?.mkdirs()
        file.writeText(body)
    }

    private fun writeChecklistItems(baseDir: File, jobFolderName: String, body: String) {
        val file = checklistItemsFile(baseDir, jobFolderName)
        file.parentFile?.mkdirs()
        file.writeText(body)
    }

    private fun specialtyItemsFile(baseDir: File, jobFolderName: String): File {
        return File(baseDir, "$jobFolderName/.metadata/admin/specialty_items.json")
    }

    private fun trackerFile(baseDir: File, jobFolderName: String, tabletId: String): File {
        return File(baseDir, "$jobFolderName/.metadata/admin/.tracker/$tabletId.json")
    }

    private fun checklistItemsFile(baseDir: File, jobFolderName: String): File {
        return File(baseDir, "$jobFolderName/.metadata/admin/checklist.json")
    }

    private fun writeMaterialMappings(baseDir: File, body: String) {
        val file = File(baseDir, ".metadata/material_mappings.json")
        file.parentFile?.mkdirs()
        file.writeText(body)
    }

    private fun writeDoorCutIndex(baseDir: File, body: String) {
        val file = File(baseDir, "$jobFolderName/.metadata/hardwoods/cutlist_index.json")
        file.parentFile?.mkdirs()
        file.writeText(body)
    }

    private fun writeCncMaterialMetadata(baseDir: File, materialName: String, partsBody: String) {
        val cncDir = File(baseDir, "$jobFolderName/CNC")
        cncDir.mkdirs()
        File(cncDir, "1234 - $materialName.pdf").writeText("pdf")
        val metadataFile = File(cncDir, ".metadata/1234 - $materialName.json")
        metadataFile.parentFile?.mkdirs()
        metadataFile.writeText(
            """
                {
                  "jobNumber": "1234",
                  "jobName": "Test Job",
                  "material": "$materialName",
                  "pdfFilename": "1234 - $materialName.pdf",
                  "pages": [
                    {
                      "pageNumber": 1,
                      "parts": $partsBody
                    }
                  ]
                }
            """.trimIndent()
        )
    }
}
