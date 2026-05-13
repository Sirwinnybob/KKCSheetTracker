package com.kkc.sheettracker.data

data class EmployeeRecord(val pin: String, val name: String)

object EmployeeDirectory {
    val records: List<EmployeeRecord> = listOf(
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
