package com.kkc.sheettracker.data.models

data class ArchiveJobEntry(
    val archiveJobId: String,
    val folderName: String,
    val jobNumber: String,
    val jobName: String,
    val archivedAt: String,
    val contentVersion: String
)
