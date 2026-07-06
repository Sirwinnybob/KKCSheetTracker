package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import java.io.File
import java.time.Instant

/**
 * The tablet's request to change the job lineup. The Hours Tracker backend polls for
 * `production_order_request.json` (beside `production_order.json`), applies it to the master
 * `production_order.json`, then deletes it. The tablet's edit is authoritative only until the
 * server consumes it.
 */
data class ProductionOrderRequest(
    val order: List<String>,
    val tabletId: String,
    val requestedAt: String
)

class ProductionOrderRequestStore(private val baseDir: File) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** Atomically writes the request file (temp + rename, copy fallback). */
    fun writeRequest(order: List<String>, tabletId: String) {
        val payload = ProductionOrderRequest(
            order = order,
            tabletId = tabletId,
            requestedAt = Instant.now().toString()
        )
        val dest = File(baseDir, "production_order_request.json")
        val tmp = File(baseDir, "production_order_request.json.tmp")
        tmp.writeText(gson.toJson(payload))
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }
}
