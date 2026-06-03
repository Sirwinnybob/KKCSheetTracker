package com.kkc.sheettracker.data

import android.content.Context
import com.google.gson.Gson
import com.kkc.sheettracker.data.models.StoredSupplyItem
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplyComment
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.data.models.SupplyAttachment
import com.kkc.sheettracker.data.models.SupplyStatusRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class SupplySubscriptionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var basePath: File
    private lateinit var repository: SupplyRepository
    private lateinit var manager: SupplySubscriptionManager
    private val gson = Gson()

    @Before
    fun setUp() {
        context = mock()
        val filesDir = tempFolder.newFolder("filesDir")
        whenever(context.filesDir).thenReturn(filesDir)

        basePath = tempFolder.newFolder("basePath")
        // Initialize basic directories in basePath
        File(basePath, ".supply/items").mkdirs()
        File(basePath, ".supply/status").mkdirs()
        File(basePath, ".supply/comments").mkdirs()

        // Create a dummy categories.json
        val cats = listOf(SupplyCategory("cat1", "Hardware", 0))
        File(basePath, ".supply/categories.json").writeText(gson.toJson(cats))

        repository = SupplyRepository(basePath.absolutePath)
        manager = SupplySubscriptionManager(context, repository)
        runBlocking { manager.initDeferred.await() }
    }

    @Test
    fun `test toggle subscriptions`() = runBlocking {
        assertFalse(manager.isItemSubscribed("item1"))
        assertFalse(manager.isCategorySubscribed("cat1"))

        manager.toggleItemSubscription("item1")
        assertTrue(manager.isItemSubscribed("item1"))

        manager.toggleItemSubscription("item1")
        assertFalse(manager.isItemSubscribed("item1"))

        manager.toggleCategorySubscription("cat1")
        assertTrue(manager.isCategorySubscribed("cat1"))
    }

    @Test
    fun `test persistence of subscriptions`() = runBlocking {
        manager.toggleItemSubscription("item2")
        manager.toggleCategorySubscription("cat2")

        // Create a new manager instance and verify it loads persisted data
        val newManager = SupplySubscriptionManager(context, repository)
        runBlocking { newManager.initDeferred.await() }
        assertTrue(newManager.isItemSubscribed("item2"))
        assertTrue(newManager.isCategorySubscribed("cat2"))
    }

    @Test
    fun `test scan for updates detects new subscription`() = runBlocking {
        // Create an item on disk
        val item = StoredSupplyItem(
            id = "item_id_1",
            categoryId = "cat1",
            name = "Screws",
            notes = null,
            createdAt = "2026-06-03T10:00:00Z",
            updatedAt = "2026-06-03T10:00:00Z"
        )
        File(basePath, ".supply/items/item_id_1.json").writeText(gson.toJson(item))

        // Initially no subscriptions
        val initialNotifications = manager.scanForUpdates()
        assertTrue(initialNotifications.isEmpty())

        // Subscribe to item
        manager.toggleItemSubscription("item_id_1")

        // Scan again - should detect as a new subscription/item (snapshot is null)
        val notifications = manager.scanForUpdates()
        assertEquals(1, notifications.size)
        assertEquals("item_id_1", notifications[0].item.id)
        assertEquals(SupplyChange.NewSubscriptionOrItem, notifications[0].changes[0])
    }

    @Test
    fun `test dismiss notification and detect changes`() = runBlocking {
        // Create item
        val item = StoredSupplyItem(
            id = "item_id_2",
            categoryId = "cat1",
            name = "Brackets",
            notes = null,
            createdAt = "2026-06-03T10:00:00Z",
            updatedAt = "2026-06-03T10:00:00Z"
        )
        File(basePath, ".supply/items/item_id_2.json").writeText(gson.toJson(item))

        // Subscribe
        manager.toggleItemSubscription("item_id_2")
        assertEquals(1, manager.scanForUpdates().size)

        // Dismiss - should clear notifications
        manager.dismissNotification("item_id_2")
        assertTrue(manager.scanForUpdates().isEmpty())

        // Update item on disk (simulating update from another device)
        val updatedItem = item.copy(
            notes = "Updated notes",
            updatedAt = "2026-06-03T11:00:00Z"
        )
        File(basePath, ".supply/items/item_id_2.json").writeText(gson.toJson(updatedItem))

        // Scan - should detect details update
        val notifications = manager.scanForUpdates()
        assertEquals(1, notifications.size)
        assertEquals(SupplyChange.DetailsUpdated, notifications[0].changes[0])
    }

    @Test
    fun `test scan for updates detects status changes`() = runBlocking {
        // Create item
        val item = StoredSupplyItem(
            id = "item_id_3",
            categoryId = "cat1",
            name = "Brackets",
            notes = null,
            createdAt = "2026-06-03T10:00:00Z",
            updatedAt = "2026-06-03T10:00:00Z"
        )
        File(basePath, ".supply/items/item_id_3.json").writeText(gson.toJson(item))

        manager.toggleItemSubscription("item_id_3")
        manager.dismissNotification("item_id_3")
        assertTrue(manager.scanForUpdates().isEmpty())

        // Add a status change record on disk
        val statusRecord = SupplyStatusRecord(status = "OUT", by = "User", at = "2026-06-03T11:00:00Z")
        File(basePath, ".supply/status/item_id_3.tablet1.json").writeText(gson.toJson(statusRecord))

        val notifications = manager.scanForUpdates()
        assertEquals(1, notifications.size)
        val change = notifications[0].changes[0]
        assertTrue(change is SupplyChange.StatusChanged)
        assertEquals("OUT", (change as SupplyChange.StatusChanged).status)
    }

    @Test
    fun `test scan for updates detects new comments`() = runBlocking {
        // Create item
        val item = StoredSupplyItem(
            id = "item_id_4",
            categoryId = "cat1",
            name = "Brackets",
            notes = null,
            createdAt = "2026-06-03T10:00:00Z",
            updatedAt = "2026-06-03T10:00:00Z"
        )
        File(basePath, ".supply/items/item_id_4.json").writeText(gson.toJson(item))

        manager.toggleItemSubscription("item_id_4")
        manager.dismissNotification("item_id_4")
        assertTrue(manager.scanForUpdates().isEmpty())

        // Add comments
        val commentDir = File(basePath, ".supply/comments/item_id_4")
        commentDir.mkdirs()
        val comment = SupplyComment(id = "c1", author = "Tester", text = "Hello", createdAt = "2026-06-03T11:00:00Z")
        File(commentDir, "c1.json").writeText(gson.toJson(comment))

        val notifications = manager.scanForUpdates()
        assertEquals(1, notifications.size)
        val change = notifications[0].changes[0]
        assertTrue(change is SupplyChange.NewComments)
        assertEquals(1, (change as SupplyChange.NewComments).count)
    }

    @Test
    fun `test scan for updates detects new attachments`() = runBlocking {
        // Create item
        val item = StoredSupplyItem(
            id = "item_id_5",
            categoryId = "cat1",
            name = "Brackets",
            notes = null,
            createdAt = "2026-06-03T10:00:00Z",
            updatedAt = "2026-06-03T10:00:00Z"
        )
        File(basePath, ".supply/items/item_id_5.json").writeText(gson.toJson(item))

        manager.toggleItemSubscription("item_id_5")
        manager.dismissNotification("item_id_5")
        assertTrue(manager.scanForUpdates().isEmpty())

        // Add attachments to item
        val updatedItem = item.copy(
            attachmentIds = listOf(SupplyAttachment("a1", "photo.jpg", "photo_stored.jpg")),
            updatedAt = "2026-06-03T11:00:00Z"
        )
        File(basePath, ".supply/items/item_id_5.json").writeText(gson.toJson(updatedItem))

        val notifications = manager.scanForUpdates()
        assertEquals(1, notifications.size)
        // There might be details updated change as well because updatedAt changed, let's find the attachments change
        val changes = notifications[0].changes
        val attachmentChange = changes.filterIsInstance<SupplyChange.NewAttachments>().firstOrNull()
        assertNotNull(attachmentChange)
        assertEquals(1, attachmentChange?.count)
    }

    @Test
    fun `test init completes exceptionally on repository error`() = runBlocking {
        val badRepository = mock<SupplyRepository>()
        whenever(badRepository.getItems()).thenThrow(RuntimeException("Repository failed"))

        val failingManager = SupplySubscriptionManager(context, badRepository)

        try {
            failingManager.initDeferred.await()
            fail("Expected initDeferred to complete exceptionally")
        } catch (t: Throwable) {
            assertEquals("Repository failed", t.message)
        }
    }
}
