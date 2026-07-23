package com.kkc.sheettracker.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SafetySubscriptionManager(private val repository: SafetyRepository) {
    private val _notificationCount = MutableStateFlow(0)
    val notificationCount: StateFlow<Int> = _notificationCount.asStateFlow()

    fun calculateNotificationCount(isSubscriber: Boolean, lastSeenCount: Int): Int {
        if (!isSubscriber) return 0
        val concerns = repository.getConcerns()
        val totalItems = concerns.size
        return (totalItems - lastSeenCount).coerceAtLeast(0)
    }

    fun updateNotificationCount(isSubscriber: Boolean, lastSeenCount: Int) {
        _notificationCount.value = calculateNotificationCount(isSubscriber, lastSeenCount)
    }
}
