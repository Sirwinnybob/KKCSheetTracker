package com.kkc.sheettracker.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class EmployeeRecord(val pin: String, val name: String)

object EmployeeDirectory {
    // Offline fallback, used only when `.time_cards\employees.json` hasn't been read yet
    // (e.g. tablet not synced). Kept intentionally small/static.
    private val fallbackRecords: List<EmployeeRecord> = listOf(
        EmployeeRecord("023", "Jonathan Thornton"),
        EmployeeRecord("067", "Jared Rosenburg"),
        EmployeeRecord("101", "Chris Tennent"),
        EmployeeRecord("189", "Kevin Leafdale"),
        EmployeeRecord("223", "Barry Roper"),
        EmployeeRecord("345", "Donald McEdward"),
        EmployeeRecord("389", "Winston Ferguson"),
        EmployeeRecord("423", "Michael Diekotto"),
        EmployeeRecord("467", "Montgomery Blackburn"),
        EmployeeRecord("501", "Cameron Baker"),
        EmployeeRecord("623", "Tye Lewin"),
        EmployeeRecord("701", "Nate Hoseteetter"),
        EmployeeRecord("901", "Kevin Olson"),
        EmployeeRecord("989", "Kevin Palmer")
    )

    private val _recordsFlow = MutableStateFlow(fallbackRecords)
    val recordsFlow: StateFlow<List<EmployeeRecord>> = _recordsFlow
    val records: List<EmployeeRecord> get() = _recordsFlow.value

    suspend fun refresh(baseDir: File) {
        val loaded = withContext(Dispatchers.IO) { loadFromDisk(baseDir) }
        if (!loaded.isNullOrEmpty()) {
            _recordsFlow.value = loaded
        }
    }

    private fun loadFromDisk(baseDir: File): List<EmployeeRecord>? {
        val employeesFile = File(File(baseDir, ".time_cards"), "employees.json")
        if (!employeesFile.isFile) return null

        return try {
            val jsonArray = org.json.JSONArray(employeesFile.readText())
            val result = mutableListOf<EmployeeRecord>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pin = obj.optString("id").trim()
                if (pin.isBlank() || obj.optBoolean("excluded", false)) continue
                val rawName = obj.optString("name").trim()
                if (rawName.isBlank()) continue
                result.add(EmployeeRecord(pin, formatName(rawName)))
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    private fun formatName(name: String): String {
        val parts = name.split(",").map { it.trim() }
        return if (parts.size == 2) "${parts[1]} ${parts[0]}" else name
    }

    fun suggestions(query: String): List<EmployeeRecord> {
        if (query.isBlank()) return emptyList()
        return records.filter { it.name.contains(query, ignoreCase = true) || it.pin.contains(query) }
    }

    fun resolveNameOrPin(input: String): String {
        val cleaned = input.trim()
        val exactPin = records.firstOrNull { it.pin == cleaned }?.name
        val exactName = records.firstOrNull { it.name.equals(cleaned, ignoreCase = true) }?.name
        return exactPin ?: exactName ?: cleaned
    }
}
