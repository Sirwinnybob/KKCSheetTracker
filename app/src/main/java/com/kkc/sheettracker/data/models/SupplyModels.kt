package com.kkc.sheettracker.data.models

data class SupplyCategory(val id: String, val name: String, val position: Int)

data class SupplyAttachment(val id: String, val originalName: String, val storedName: String)

data class SupplyItem(
    val id: String,
    val categoryId: String,
    val name: String,
    val status: String,
    val notes: String?,
    val fields: Map<String, String>,
    val customFields: Map<String, String>,
    val attachmentIds: List<SupplyAttachment>,
    val createdAt: String,
    val updatedAt: String
)

data class SupplyComment(val id: String, val author: String, val text: String, val createdAt: String)

data class SupplySchemaField(
    val id: String,
    val key: String,
    val label: String,
    val type: String,
    val builtin: Boolean
)

// Priority tier for sorting — lower = more urgent (floats to top)
val SUPPLY_STATUS_PRIORITY: Map<String, Int> = mapOf(
    "OUT" to 1, "ASAP" to 1,
    "MALFUNCTIONING" to 2, "NEED" to 2,
    "LOW" to 3,
    "ORDERED" to 4, "IN PROCESS" to 4, "ACKNOWLEDGED" to 4,
    "IN STOCK" to 5, "COMPLETE" to 5, "RECEIVED" to 5
)

val ALL_SUPPLY_STATUSES = listOf(
    "OUT", "ASAP", "MALFUNCTIONING", "NEED", "LOW",
    "ORDERED", "IN PROCESS", "ACKNOWLEDGED",
    "IN STOCK", "COMPLETE", "RECEIVED"
)
