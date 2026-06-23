package com.kkc.sheettracker.data

import android.content.Context
import com.google.gson.Gson
import com.kkc.sheettracker.data.models.SupplyComment
import com.kkc.sheettracker.data.models.SupplyItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

sealed class SupplyChange {
    object NewSubscriptionOrItem : SupplyChange()
    object DetailsUpdated : SupplyChange()
    data class StatusChanged(val status: String) : SupplyChange()
    data class NewComments(val count: Int) : SupplyChange()
    data class NewAttachments(val count: Int) : SupplyChange()
}

class SupplySubscriptionManager(
    private val context: Context,
    private val repository: SupplyRepository
) {
    private val gson = Gson()
    private val subscriptionsFile = File(context.filesDir, FILE_NAME)

    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val initDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

    private val _subscriptionData = MutableStateFlow(SupplySubscriptionData())
    val subscriptionData: StateFlow<SupplySubscriptionData> = _subscriptionData.asStateFlow()

    private val _notificationCount = MutableStateFlow(0)
    val notificationCount: StateFlow<Int> = _notificationCount.asStateFlow()

    init {
        scope.launch {
            try {
                val data = loadData()
                _subscriptionData.value = data
                scanForUpdatesInternal()
                initDeferred.complete(Unit)
            } catch (t: Throwable) {
                initDeferred.completeExceptionally(t)
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun logError(message: String, throwable: Throwable) {
        try {
            android.util.Log.e("SupplySubscriptionManager", message, throwable)
        } catch (e: Throwable) {
            System.err.println("SupplySubscriptionManager: $message: ${throwable.message}")
            throwable.printStackTrace()
        }
    }

    suspend fun loadData(): SupplySubscriptionData = withContext(Dispatchers.IO) {
        if (!subscriptionsFile.exists()) return@withContext SupplySubscriptionData()
        runCatching {
            val parsed = gson.fromJson(subscriptionsFile.readText(), SupplySubscriptionData::class.java)
            sanitizeSubscriptionData(parsed)
        }.onFailure {
            logError("Failed to load subscription data", it)
        }.getOrDefault(SupplySubscriptionData())
    }

    private suspend fun saveDataInternal(data: SupplySubscriptionData) = withContext(Dispatchers.IO) {
        _subscriptionData.value = data
        runCatching {
            subscriptionsFile.parentFile?.mkdirs()
            subscriptionsFile.writeText(gson.toJson(data))
        }.onFailure {
            logError("Failed to save subscription data", it)
        }
        scanForUpdatesInternal()
    }

    suspend fun toggleItemSubscription(itemId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _subscriptionData.value
            val items = current.subscribedItemIds.toMutableSet()
            if (items.contains(itemId)) {
                items.remove(itemId)
            } else {
                items.add(itemId)
            }
            saveDataInternal(current.copy(subscribedItemIds = items))
        }
    }

    suspend fun toggleCategorySubscription(categoryId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _subscriptionData.value
            val categories = current.subscribedCategoryIds.toMutableSet()
            if (categories.contains(categoryId)) {
                categories.remove(categoryId)
            } else {
                categories.add(categoryId)
            }
            saveDataInternal(current.copy(subscribedCategoryIds = categories))
        }
    }

    fun isItemSubscribed(itemId: String): Boolean {
        return _subscriptionData.value.subscribedItemIds.contains(itemId)
    }

    fun isCategorySubscribed(categoryId: String): Boolean {
        return _subscriptionData.value.subscribedCategoryIds.contains(categoryId)
    }

    suspend fun dismissNotification(itemId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val currentItem = repository.getItem(itemId) ?: return@withContext
            val currentComments = repository.getComments(itemId)
            val snapshot = SupplyItemSnapshot(
                lastSeenUpdatedAt = currentItem.updatedAt,
                lastSeenStatusAt = currentItem.statusAt,
                lastSeenCommentIds = currentComments.map { it.id },
                lastSeenAttachmentIds = currentItem.attachmentIds.map { it.id }
            )
            val currentData = _subscriptionData.value
            val updatedSnapshots = currentData.itemSnapshots.toMutableMap()
            updatedSnapshots[itemId] = snapshot
            saveDataInternal(currentData.copy(itemSnapshots = updatedSnapshots))
        }
    }

    suspend fun scanForUpdates(): List<SupplyNotificationItem> = mutex.withLock {
        scanForUpdatesInternal()
    }

    private suspend fun scanForUpdatesInternal(): List<SupplyNotificationItem> = withContext(Dispatchers.IO) {
        val data = _subscriptionData.value
        val allItems = repository.getItems()
        val subscribedItems = allItems.filter { item ->
            data.subscribedItemIds.contains(item.id) || data.subscribedCategoryIds.contains(item.categoryId)
        }

        val notifications = mutableListOf<SupplyNotificationItem>()

        for (item in subscribedItems) {
            val comments = repository.getComments(item.id)
            val snapshot = data.itemSnapshots[item.id]

            if (snapshot == null) {
                // If there's no snapshot, treat it as a new subscription/item
                notifications.add(
                    SupplyNotificationItem(
                        item = item,
                        changes = listOf(SupplyChange.NewSubscriptionOrItem),
                        comments = comments
                    )
                )
                continue
            }

            val changes = mutableListOf<SupplyChange>()

            // 1. Details change (updatedAt)
            if (item.updatedAt != snapshot.lastSeenUpdatedAt && item.updatedAt.isNotBlank()) {
                changes.add(SupplyChange.DetailsUpdated)
            }

            // 2. Status change (statusAt)
            if (item.statusAt != snapshot.lastSeenStatusAt && item.statusAt.isNotBlank()) {
                changes.add(SupplyChange.StatusChanged(item.status))
            }

            // 3. New comments
            val newComments = comments.filter { !snapshot.lastSeenCommentIds.contains(it.id) }
            if (newComments.isNotEmpty()) {
                changes.add(SupplyChange.NewComments(newComments.size))
            }

            // 4. New attachments
            val newAttachments = item.attachmentIds.filter { !snapshot.lastSeenAttachmentIds.contains(it.id) }
            if (newAttachments.isNotEmpty()) {
                changes.add(SupplyChange.NewAttachments(newAttachments.size))
            }

            if (changes.isNotEmpty()) {
                notifications.add(
                    SupplyNotificationItem(
                        item = item,
                        changes = changes,
                        comments = comments
                    )
                )
            }
        }

        _notificationCount.value = notifications.size
        notifications
    }

    private fun sanitizeSubscriptionData(data: SupplySubscriptionData?): SupplySubscriptionData {
        if (data == null) return SupplySubscriptionData()
        return SupplySubscriptionData(
            subscribedCategoryIds = data.subscribedCategoryIds ?: emptySet(),
            subscribedItemIds = data.subscribedItemIds ?: emptySet(),
            itemSnapshots = (data.itemSnapshots ?: emptyMap()).mapValues { (_, snapshot) ->
                SupplyItemSnapshot(
                    lastSeenUpdatedAt = snapshot.lastSeenUpdatedAt ?: "",
                    lastSeenStatusAt = snapshot.lastSeenStatusAt ?: "",
                    lastSeenCommentIds = snapshot.lastSeenCommentIds ?: emptyList(),
                    lastSeenAttachmentIds = snapshot.lastSeenAttachmentIds ?: emptyList()
                )
            }
        )
    }

    companion object {
        const val FILE_NAME = "supply_subscriptions.json"
    }
}

data class SupplySubscriptionData(
    val subscribedCategoryIds: Set<String> = emptySet(),
    val subscribedItemIds: Set<String> = emptySet(),
    val itemSnapshots: Map<String, SupplyItemSnapshot> = emptyMap()
)

data class SupplyItemSnapshot(
    val lastSeenUpdatedAt: String,
    val lastSeenStatusAt: String,
    val lastSeenCommentIds: List<String>,
    val lastSeenAttachmentIds: List<String>
)

data class SupplyNotificationItem(
    val item: SupplyItem,
    val changes: List<SupplyChange>,
    val comments: List<SupplyComment>
)
