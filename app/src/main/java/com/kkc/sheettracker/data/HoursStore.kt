package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kkc.sheettracker.data.models.HoursDayLog
import com.kkc.sheettracker.data.models.HoursEntry
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

class HoursStore(
    private val baseDir: File,
    private val tabletId: String
) {
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    private fun fileForDate(date: LocalDate): File {
        val dir = File(baseDir, "HoursData")
        dir.mkdirs()
        return File(dir, "${date.format(DATE_FMT)}.json")
    }

    private fun readLog(date: LocalDate): HoursDayLog {
        val file = fileForDate(date)
        if (!file.exists()) return HoursDayLog(date = date.format(DATE_FMT))
        return runCatching {
            gson.fromJson(file.readText(), HoursDayLog::class.java)
        }.getOrDefault(HoursDayLog(date = date.format(DATE_FMT)))
    }

    private fun writeLog(log: HoursDayLog) {
        fileForDate(LocalDate.parse(log.date, DATE_FMT)).writeText(gson.toJson(log))
    }

    fun clockIn(employeeName: String, date: LocalDate = LocalDate.now()): HoursEntry {
        val log = readLog(date)
        val entry = HoursEntry(
            id = UUID.randomUUID().toString(),
            employeeName = employeeName,
            clockInMs = System.currentTimeMillis(),
            clockOutMs = null,
            tabletId = tabletId
        )
        writeLog(log.copy(entries = log.entries + entry))
        return entry
    }

    fun clockOut(entryId: String, date: LocalDate = LocalDate.now()): HoursEntry? {
        val log = readLog(date)
        val updated = log.entries.map { entry ->
            if (entry.id == entryId && entry.clockOutMs == null) {
                entry.copy(clockOutMs = System.currentTimeMillis())
            } else {
                entry
            }
        }
        val result = updated.find { it.id == entryId }
        writeLog(log.copy(entries = updated))
        return result
    }

    fun getEntriesForDate(date: LocalDate = LocalDate.now()): List<HoursEntry> {
        return readLog(date).entries
    }

    fun getActiveEntry(employeeName: String, date: LocalDate = LocalDate.now()): HoursEntry? {
        return readLog(date).entries
            .lastOrNull { it.employeeName == employeeName && it.clockOutMs == null }
    }
}
