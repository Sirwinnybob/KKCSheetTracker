package com.kkc.sheettracker.data.models

data class StoredSafetyConcern(
    val id: String,
    val author: String,
    val title: String,
    val category: String,
    val description: String,
    val attachmentIds: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = ""
) {
    constructor() : this(id = "", author = "", title = "", category = "", description = "")
}

data class SafetyStatusRecord(
    val status: String,
    val by: String = "",
    val at: String = ""
)

data class SafetyItem(
    val id: String,
    val author: String,
    val title: String,
    val category: String,
    val description: String,
    val status: String,
    val statusBy: String,
    val statusAt: String,
    val attachmentIds: List<String>,
    val createdAt: String,
    val updatedAt: String
)

data class SafetyComment(
    val id: String,
    val author: String,
    val text: String,
    val createdAt: String
)

val ALL_SAFETY_STATUSES = listOf(
    "OPEN", "ACKNOWLEDGED", "IN PROGRESS", "RESOLVED"
)

val SAFETY_CATEGORIES = listOf(
    "Near Miss",
    "Equipment Hazard",
    "SDS / Chemical",
    "Housekeeping / Slip Hazard",
    "General Suggestion"
)
