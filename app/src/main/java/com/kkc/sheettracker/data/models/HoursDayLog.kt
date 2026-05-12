package com.kkc.sheettracker.data.models

data class HoursDayLog(
    val date: String,              // "YYYY-MM-DD"
    val entries: List<HoursEntry> = emptyList()
)
