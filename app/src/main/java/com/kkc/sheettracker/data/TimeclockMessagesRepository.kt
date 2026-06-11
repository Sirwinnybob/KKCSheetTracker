package com.kkc.sheettracker.data

import android.util.Log
import com.google.gson.Gson
import java.io.File

class TimeclockMessagesRepository(private val baseDir: File) {

    private val gson = Gson()

    fun clockInMessage(isLunch: Boolean): String {
        val msgs = load()
        val pool = if (isLunch) msgs.clock_in_lunch else msgs.clock_in_morning
        return pool.randomOrNull()
            ?: if (isLunch) "Welcome back from lunch!" else "Clocked in! Have a great shift."
    }

    fun clockOutMessage(isLunch: Boolean, hoursWorked: Double?): String {
        val msgs = load()
        val pool = if (isLunch) msgs.clock_out_lunch else msgs.clock_out_evening
        val msg = pool.randomOrNull()
            ?: if (isLunch) "Enjoy your lunch!" else "See you tomorrow!"
        return if (hoursWorked != null) "$msg\n${"%.2f".format(hoursWorked)} hrs today" else msg
    }

    private fun load(): MessagesData {
        return try {
            val file = File(baseDir, ".metadata/timeclock_messages.json")
            if (!file.exists()) return MessagesData()
            gson.fromJson(file.readText(), MessagesData::class.java) ?: MessagesData()
        } catch (e: Exception) {
            Log.w("TimeclockMessages", "Failed to load messages: ${e.message}")
            MessagesData()
        }
    }

    private data class MessagesData(
        val clock_in_morning: List<String> = emptyList(),
        val clock_in_lunch: List<String> = emptyList(),
        val clock_out_lunch: List<String> = emptyList(),
        val clock_out_evening: List<String> = emptyList()
    )
}
