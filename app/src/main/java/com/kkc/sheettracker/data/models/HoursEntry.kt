package com.kkc.sheettracker.data.models

data class HoursEntry(
    val id: String,
    val employeeName: String,
    val clockInMs: Long,
    val clockOutMs: Long?,
    val tabletId: String
)
