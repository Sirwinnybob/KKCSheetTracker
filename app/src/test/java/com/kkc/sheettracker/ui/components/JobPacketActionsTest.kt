package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.models.JobPdfCatalog
import com.kkc.sheettracker.data.models.JobPdfRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JobPacketActionsTest {

    @Test
    fun jobPacketDocuments_matchesExactStemsOrPacketSuffixesIgnoringCase() {
        val catalog = JobPdfCatalog(
            otherDocs = listOf(
                JobPdfRef("Legacy Folder Name - handoff sheet.PDF"),
                JobPdfRef("Different Prefix - DELIVERY CHECKLIST.pdf"),
                JobPdfRef("Other Job - J123 - HANDOFF SHEET.pdf"),
                JobPdfRef("J123 - old handoff sheet.pdf"),
                JobPdfRef("J123 - HANDOFF SHEET - revised.pdf"),
                JobPdfRef("J123 - HANDOFF SHEET.pdf.backup"),
                JobPdfRef(" - HANDOFF SHEET.pdf"),
                JobPdfRef("HANDOFF SHEET.PDF"),
                JobPdfRef("DELIVERY CHECKLIST.pdf")
            )
        )

        assertEquals(
            listOf(
                JobPdfRef("Legacy Folder Name - handoff sheet.PDF", "Handoff Sheet"),
                JobPdfRef("Different Prefix - DELIVERY CHECKLIST.pdf", "Delivery Checklist"),
            ),
            jobPacketDocuments(catalog)
        )
    }

    @Test
    fun jobPacketDocuments_rejectsBlankNonPdfAndSyncConflictEntries() {
        val catalog = JobPdfCatalog(
            otherDocs = listOf(
                JobPdfRef(" "),
                JobPdfRef("J123 - HANDOFF SHEET.docx"),
                JobPdfRef("J123 - DELIVERY CHECKLIST.pdf.sync-conflict-20261014"),
                JobPdfRef("J123 - HANDOFF SHEET.sync-conflict-copy.pdf")
            )
        )

        assertEquals(emptyList<JobPdfRef>(), jobPacketDocuments(catalog))
    }

    @Test
    fun jobPacketDocuments_returnsEmptyWhenTheCatalogHasNoPacketDocuments() {
        assertEquals(emptyList<JobPdfRef>(), jobPacketDocuments(JobPdfCatalog()))
        assertEquals(
            emptyList<JobPdfRef>(),
            jobPacketDocuments(JobPdfCatalog(otherDocs = listOf(JobPdfRef("Unrelated.pdf"))))
        )
    }

    @Test
    fun jobPacketActions_joinTheReferenceSideOfEachWrappedTopRow() {
        val projectRoot = findProjectRoot()
        val screenSources = mapOf(
            "CNC" to File(projectRoot, "app/src/main/java/com/kkc/sheettracker/ui/detail/JobDetailScreen.kt").readText(),
            "Hardwoods" to File(projectRoot, "app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobDetailScreen.kt").readText(),
            "Assembly" to File(projectRoot, "app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyJobDetailScreen.kt").readText(),
            "Specialty" to File(projectRoot, "app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt").readText()
        )

        screenSources.forEach { (screen, source) ->
            assertTrue("$screen must expose the packet-document callback", source.contains("onOpenJobPacketDocument: (JobPdfRef) -> Unit"))
            assertTrue("$screen must load shared packet actions", source.contains("rememberJobPacketActions("))
        }
        val packetActionsSource = File(projectRoot, "app/src/main/java/com/kkc/sheettracker/ui/components/JobPacketActions.kt").readText()
        assertTrue(packetActionsSource.contains("key(jobRepository, jobFolderName)"))
        val pillSource = File(projectRoot, "app/src/main/java/com/kkc/sheettracker/ui/components/KKCSlidingPill.kt").readText()
        assertTrue("Action rows must keep wrapping opt-in", pillSource.contains("wrap: Boolean = false"))
        assertTrue("Wrapped action rows must use FlowRow", pillSource.contains("FlowRow("))
        assertTrue("Both packet buttons must use the shipping icon", packetActionsSource.contains("Icons.Filled.LocalShipping"))
        listOf("CNC", "Hardwoods").forEach { screen ->
            val source = screenSources.getValue(screen)
            assertTrue("$screen must keep its reference actions on the left", source.contains("actions = referenceActions"))
            assertTrue("$screen must place packet actions on the right", source.contains("trailingActions = jobPacketActions"))
            assertTrue("$screen top row must wrap", Regex("trailingActions = jobPacketActions,\\s*fillWidth = true,\\s*wrap = true").containsMatchIn(source))
            assertEquals("$screen must add packet actions only once", 1, Regex("trailingActions = jobPacketActions").findAll(source).count())
            assertTrue("$screen must not duplicate packet actions on the left", !source.contains("addAll(jobPacketActions)"))
        }
        val assembly = screenSources.getValue("Assembly")
        assertTrue(assembly.contains("actions = listOf(KKCPillAction(\"Split View\", onOpenSplitView))"))
        assertTrue(Regex("trailingActions = jobPacketActions,\\s*fillWidth = true,\\s*wrap = true").containsMatchIn(assembly))
        val specialty = screenSources.getValue("Specialty")
        assertEquals(2, Regex("actions = specialtyActions,\\s*trailingActions = referenceActions \\+ jobPacketActions,\\s*fillWidth = true,\\s*wrap = true")
            .findAll(specialty).count())
        assertTrue("Specialty must not create a separate packet row", !Regex("actions = jobPacketActions,\\s*fillWidth").containsMatchIn(specialty))
    }

    private fun findProjectRoot(): File {
        var projectRoot = File(System.getProperty("user.dir") ?: error("Missing working directory"))
        while (!File(projectRoot, "app/src/main").isDirectory) {
            projectRoot = projectRoot.parentFile ?: error("Unable to locate the project root")
        }
        return projectRoot
    }
}
