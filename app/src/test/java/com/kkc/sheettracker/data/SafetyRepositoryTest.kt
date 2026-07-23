package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafetyRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAddAndGetSafetyConcern() {
        val repository = SafetyRepository(tempFolder.root.absolutePath)
        val item = repository.addConcern(
            author = "John Doe",
            title = "Loose Guard",
            category = "Equipment Hazard",
            description = "Guard on saw 2 is loose",
            attachmentIds = emptyList(),
            tabletId = "tablet-1"
        )
        assertNotNull(item.id)
        assertEquals("OPEN", item.status)

        val retrieved = repository.getConcerns()
        assertEquals(1, retrieved.size)
        assertEquals("Loose Guard", retrieved[0].title)
    }

    @Test
    fun testSetStatusAndComments() {
        val repository = SafetyRepository(tempFolder.root.absolutePath)
        val item = repository.addConcern(
            author = "John Doe",
            title = "Slip Hazard",
            category = "Housekeeping / Slip Hazard",
            description = "Oil spill on floor",
            attachmentIds = emptyList(),
            tabletId = "tablet-1"
        )

        repository.setStatus(item.id, "ACKNOWLEDGED", "Supervisor", "tablet-2")
        val updatedConcerns = repository.getConcerns()
        assertEquals("ACKNOWLEDGED", updatedConcerns[0].status)
        assertEquals("Supervisor", updatedConcerns[0].statusBy)

        val comment = repository.addComment(item.id, "Supervisor", "Cleanup in progress")
        val comments = repository.getComments(item.id)
        assertEquals(1, comments.size)
        assertEquals("Cleanup in progress", comments[0].text)
    }
}
