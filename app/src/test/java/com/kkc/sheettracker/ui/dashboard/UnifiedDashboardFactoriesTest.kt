package com.kkc.sheettracker.ui.dashboard

import com.kkc.sheettracker.data.models.AssemblyCncSummary
import com.kkc.sheettracker.data.models.AssemblyHardwoodsSummary
import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.DashboardRecentMaterialItem
import com.kkc.sheettracker.data.models.DashboardUiModel
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDashboardFactoriesTest {

    @Test
    fun `buildCncDashboardWidgets includes sorted recents and quality accents`() {
        val widgets = buildCncDashboardWidgets(
            DashboardUiModel(
                totalJobs = 5,
                totalSheets = 20,
                completedSheets = 12,
                badPartsSheets = 2,
                skippedSheets = 1,
                recentInProgressMaterials = listOf(
                    DashboardRecentMaterialItem(
                        jobFolderName = "JOB-101",
                        jobNumber = "101",
                        materialName = "Maple Veneer",
                        pdfFilename = "job101.pdf",
                        fileFingerprint = "def",
                        lastTouchedPage = 1,
                        nextIncompletePage = 2,
                        lastTouchedAtMs = 456L,
                        counts = StatusCounts(total = 4, complete = 1, bad = 0, skipped = 1, notStarted = 2),
                        completionFraction = 0.25f
                    ),
                    DashboardRecentMaterialItem(
                        jobFolderName = "JOB-100",
                        jobNumber = "100",
                        materialName = "White Melamine",
                        pdfFilename = "job100.pdf",
                        fileFingerprint = "abc",
                        lastTouchedPage = 2,
                        nextIncompletePage = 3,
                        lastTouchedAtMs = 123L,
                        counts = StatusCounts(total = 6, complete = 2, bad = 1, skipped = 0, notStarted = 3),
                        completionFraction = 0.33f
                    )
                )
            )
        )

        assertTrue(widgets.any { it is DashboardWidgetModel.Hero })
        assertTrue(widgets.any { it is DashboardWidgetModel.StatsRow })
        assertTrue(widgets.any { it is DashboardWidgetModel.AlertBlock })
        assertTrue(widgets.any { it is DashboardWidgetModel.RecentItemsBlock })

        val hero = widgets.requireSingle<DashboardWidgetModel.Hero>()
        assertTrue(hero.primaryValue.contains("12"))
        assertTrue(hero.primaryValue.contains("20"))
        assertEquals(DashboardAccent.DANGER, hero.accent)

        val alertBlock = widgets.requireSingle<DashboardWidgetModel.AlertBlock>()
        assertNotNull(alertBlock.supportingText)
        assertTrue(alertBlock.supportingText!!.contains("8"))
        assertTrue(alertBlock.supportingText!!.contains("remaining"))
        assertEquals(DashboardAccent.DANGER, alertBlock.accent)

        val recentBlock = widgets.requireSingle<DashboardWidgetModel.RecentItemsBlock>()
        assertEquals(2, recentBlock.items.size)
        assertEquals("Maple Veneer", recentBlock.items[0].title)
        assertEquals("White Melamine", recentBlock.items[1].title)
        assertEquals(DashboardAccent.WARNING, recentBlock.items[0].accent)
        assertEquals(DashboardAccent.DANGER, recentBlock.items[1].accent)
    }

    @Test
    fun `buildAssemblyDashboardWidgets includes specialty summary and job list`() {
        val widgets = buildAssemblyDashboardWidgets(
            cards = listOf(
                AssemblyJobCard(
                    folderName = "JOB-200",
                    jobNumber = "200",
                    jobName = "Kitchen",
                    cncSummary = AssemblyCncSummary(completedSheets = 4, totalSheets = 8),
                    hardwoodsSummary = AssemblyHardwoodsSummary(donePieces = 12, totalPieces = 16),
                    hasBothModes = true
                )
            ),
            specialtyStatus = ScanStatus.READY,
            specialtySummary = SpecialtySummary(jobCount = 1, completedItems = 7, totalItems = 10),
            totalCabinets = 14
        )

        assertTrue(widgets.any { it is DashboardWidgetModel.Hero })
        assertTrue(widgets.any { it is DashboardWidgetModel.AlertBlock })
        assertTrue(widgets.any { it is DashboardWidgetModel.JobsBlock })

        val specialtyBlock = widgets.requireSingle<DashboardWidgetModel.AlertBlock>()
        assertEquals("Specialty", specialtyBlock.title)
        assertTrue(specialtyBlock.message.contains("7 / 10"))
        assertEquals(DashboardAccent.INFO, specialtyBlock.accent)

        val jobsBlock = widgets.requireSingle<DashboardWidgetModel.JobsBlock>()
        assertNotNull(jobsBlock.summary)
        assertTrue(jobsBlock.summary!!.contains("14"))
        assertTrue(jobsBlock.summary!!.contains("cabinet"))
        assertEquals(0.625f, jobsBlock.items.first().progressFraction ?: 0f, 0.0001f)
    }

    @Test
    fun `buildAssemblyDashboardWidgets keeps single mode job progress intact`() {
        val widgets = buildAssemblyDashboardWidgets(
            cards = listOf(
                AssemblyJobCard(
                    folderName = "JOB-201",
                    jobNumber = "201",
                    jobName = "Pantry",
                    cncSummary = AssemblyCncSummary(completedSheets = 3, totalSheets = 4),
                    hardwoodsSummary = AssemblyHardwoodsSummary(),
                    hasBothModes = false
                )
            ),
            specialtyStatus = ScanStatus.IDLE,
            specialtySummary = SpecialtySummary(),
            totalCabinets = 6
        )

        val jobsBlock = widgets.requireSingle<DashboardWidgetModel.JobsBlock>()
        assertEquals(0.75f, jobsBlock.items.first().progressFraction ?: 0f, 0.0001f)
        assertEquals(DashboardAccent.INFO, jobsBlock.items.first().accent)
    }

    @Test
    fun `buildSupplyCategoryWidgets sorts by priority and exposes summary`() {
        val widgets = buildSupplyCategoryWidgets(
            category = SupplyCategory(id = "cat-1", name = "Hardware", position = 1),
            items = listOf(
                SupplyItem(
                    id = "item-2",
                    categoryId = "cat-1",
                    name = "Drawer Slide",
                    status = "IN STOCK",
                    statusBy = "",
                    statusAt = "",
                    notes = null,
                    fields = mapOf("quantity" to "10"),
                    customFields = emptyMap(),
                    attachmentIds = emptyList(),
                    createdAt = "",
                    updatedAt = ""
                ),
                SupplyItem(
                    id = "item-3",
                    categoryId = "cat-1",
                    name = "Touch Up Kit",
                    status = "OUT",
                    statusBy = "",
                    statusAt = "",
                    notes = "Backorder",
                    fields = mapOf("quantity" to "0"),
                    customFields = emptyMap(),
                    attachmentIds = emptyList(),
                    createdAt = "",
                    updatedAt = ""
                ),
                SupplyItem(
                    id = "item-1",
                    categoryId = "cat-1",
                    name = "Blum Hinge",
                    status = "LOW",
                    statusBy = "",
                    statusAt = "",
                    notes = null,
                    fields = mapOf("quantity" to "4"),
                    customFields = emptyMap(),
                    attachmentIds = emptyList(),
                    createdAt = "",
                    updatedAt = ""
                )
            ),
            isSubscribed = true,
            notificationCount = 3
        )

        assertTrue(widgets.any { it is DashboardWidgetModel.Hero })
        assertTrue(widgets.any { it is DashboardWidgetModel.InventoryBlock })

        val hero = widgets.requireSingle<DashboardWidgetModel.Hero>()
        assertEquals(DashboardAccent.WARNING, hero.accent)
        assertNotNull(hero.tertiaryValue)
        assertTrue(hero.tertiaryValue!!.contains("3"))
        assertTrue(hero.tertiaryValue!!.contains("notification"))

        val inventoryBlock = widgets.requireSingle<DashboardWidgetModel.InventoryBlock>()
        assertEquals("Hardware", inventoryBlock.title)
        assertNotNull(inventoryBlock.summary)
        assertTrue(inventoryBlock.summary!!.contains("2 urgent"))
        assertEquals(listOf("Touch Up Kit", "Blum Hinge", "Drawer Slide"), inventoryBlock.items.map { it.title })
        assertEquals(DashboardAccent.DANGER, inventoryBlock.items.first().accent)
    }

    private inline fun <reified T : DashboardWidgetModel> List<DashboardWidgetModel>.requireSingle(): T {
        val matches = filterIsInstance<T>()
        assertEquals(1, matches.size)
        return matches.single()
    }
}
