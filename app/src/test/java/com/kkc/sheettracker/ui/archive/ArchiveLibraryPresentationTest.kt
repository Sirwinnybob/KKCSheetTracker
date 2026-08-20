package com.kkc.sheettracker.ui.archive

import com.kkc.sheettracker.data.models.ArchiveJobEntry
import com.kkc.sheettracker.ui.components.NavBarDecorationState
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

class ArchiveLibraryPresentationTest {

    private val entries = listOf(
        ArchiveJobEntry(
            archiveJobId = "archive-1",
            folderName = "2026-0142 Smith Kitchen",
            jobNumber = "0142",
            jobName = "Smith Kitchen",
            archivedAt = "2026-08-20T12:00:00Z",
            contentVersion = "v1",
        ),
        ArchiveJobEntry(
            archiveJobId = "archive-2",
            folderName = "Cabinets - Oak Ridge",
            jobNumber = "0901",
            jobName = "Guest Suite",
            archivedAt = "2026-08-19T12:00:00Z",
            contentVersion = "v2",
        ),
    )

    @Test
    fun `filterArchiveEntries matches job number name and folder without case sensitivity`() {
        assertEquals(listOf(entries[0]), filterArchiveEntries(entries, "142"))
        assertEquals(listOf(entries[0]), filterArchiveEntries(entries, "SMITH"))
        assertEquals(listOf(entries[1]), filterArchiveEntries(entries, "oak ridge"))
    }

    @Test
    fun `archive navigation decoration is owned only while the archive route is active`() {
        val navBar = NavBarDecorationState()
        val archiveSearch = NavBarSearchDecoration(
            searchTextValue = TextFieldValue(),
            onSearchTextChange = {},
            onGo = {},
            isPartsEnabled = false,
            onParts = {},
            contextLine = "",
            placeholder = "Search archive…",
            showParts = false,
        )

        updateArchiveNavBarDecoration(navBar, active = true, searchDecoration = archiveSearch)

        assertEquals("archive_library", navBar.owner)
        assertEquals(archiveSearch, navBar.searchDecoration)

        updateArchiveNavBarDecoration(navBar, active = false, searchDecoration = archiveSearch)

        assertEquals("", navBar.owner)
        assertNull(navBar.searchDecoration)
    }

    @Test
    fun `archive list keeps a single 150 dp scroll clearance beneath navbar`() {
        assertEquals(150.dp, archiveScreenBottomPadding())
    }

    @Test
    fun `archive download lifecycle allows one active job and ignores stale completion`() {
        assertTrue(canStartArchiveOpen(activeArchiveJobId = null))
        assertFalse(canStartArchiveOpen(activeArchiveJobId = "archive-1"))
        assertTrue(shouldClearArchiveDownload(activeArchiveJobId = "archive-1", completedArchiveJobId = "archive-1"))
        assertFalse(shouldClearArchiveDownload(activeArchiveJobId = "archive-2", completedArchiveJobId = "archive-1"))
    }

    @Test
    fun `archive restore action is unavailable while that row is opening`() {
        assertFalse(canRestoreArchivedJob(opening = true))
        assertTrue(canRestoreArchivedJob(opening = false))
    }
}
