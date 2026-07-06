package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS
import java.io.File

/**
 * Reads the delivery schedule from the shared network drive.
 * Storage path: {baseDir}/.metadata/delivery_schedule.json
 * Written by kkc-admin; read-only on the tablet.
 * Call on Dispatchers.IO.
 */
class DeliveryScheduleRepository(private val baseDir: File) {

    private val gson = GsonBuilder().create()

    fun fetchSchedule(): DeliverySchedule {
        val file = File(baseDir, ".metadata/delivery_schedule.json")
        if (!file.exists() || !file.isFile) return DeliverySchedule()
        return runCatching { parseSchedule(file.readText()) }.getOrElse { DeliverySchedule() }
    }

    private fun parseSchedule(json: String): DeliverySchedule {
        val root = gson.fromJson(json, JsonObject::class.java) ?: return DeliverySchedule()
        val slotsObj = root.getAsJsonObject("slots") ?: return DeliverySchedule()
        val slots = mutableMapOf<String, DeliverySlot>()

        for (day in DELIVERY_DAYS) {
            for (period in DELIVERY_PERIODS) {
                val key = "${day}_${period}"
                val slotObj = slotsObj.getAsJsonObject(key)
                val jobs = mutableListOf<DeliveryJob>()
                if (slotObj != null) {
                    val jobsArr = slotObj.getAsJsonArray("jobs")
                    jobsArr?.forEach { elem ->
                        val obj = elem.asJsonObject
                        jobs.add(
                            DeliveryJob(
                                jobNumber = obj.get("jobNumber")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                address = obj.get("address")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                folderName = obj.get("folderName")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            )
                        )
                    }
                }
                slots[key] = DeliverySlot(jobs = jobs)
            }
        }
        return DeliverySchedule(slots = slots)
    }
}
