package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import java.io.File
import java.time.Instant

/**
 * The tablet's request to change the job lineup. The Hours Tracker backend polls for per-tablet
 * `production_order_request.<tabletId>.json` files (beside `production_order.json`), applies
 * each to the master `production_order.json` (oldest-first by `requestedAt`), then deletes each
 * individually. The tablet's edit is authoritative only until the server consumes it.
 *
 * CROSS-PROGRAM (see METADATA_AUDIT.md M-04): this used to be one shared
 * `production_order_request.json` — two tablets queuing an edit before the same poll cycle
 * collided (the second write clobbered the first, or Syncthing quarantined one copy as an
 * unread `.sync-conflict-*` file). Each tablet now owns a distinct filename keyed on its own
 * tabletId; the backend poller (`main_v2.py` `_apply_production_order_requests`) globs all
 * matching files and applies them in timestamp order.
 */
data class ProductionOrderRequest(
    val order: List<String>,
    val tabletId: String,
    val requestedAt: String
)

class ProductionOrderRequestStore(private val baseDir: File) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** Atomically writes this tablet's own request file (temp + ATOMIC_MOVE, see AtomicFileWriter). */
    fun writeRequest(order: List<String>, tabletId: String) {
        val payload = ProductionOrderRequest(
            order = order,
            tabletId = tabletId,
            requestedAt = Instant.now().toString()
        )
        val dest = File(baseDir, "production_order_request.$tabletId.json")
        atomicWriteFile(dest, gson.toJson(payload))
    }
}
