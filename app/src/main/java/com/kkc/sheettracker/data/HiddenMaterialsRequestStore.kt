package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import java.io.File

/**
 * The tablet's durable backup write for a hide/unhide action, consumed by Hours Tracker's
 * sidecar-request poller (main_v2.py's `_apply_hidden_materials_request`) when the fast REST
 * path (AdminSyncClient.applyHiddenMaterialsAction) can't be reached. Written beside the master
 * file it targets -- the mode's global dir for `scope == "global"`, or that job's mode dir for
 * `scope == "job"` -- one file per tablet (`hidden_materials_request.<tabletId>.json`) so two
 * tablets queuing an edit before the same poll cycle never collide, matching
 * ProductionOrderRequestStore's pattern (see METADATA_AUDIT.md M-04).
 */
data class HiddenMaterialsRequest(
    val mode: String,
    val action: String,
    val scope: String,
    val docType: String,
    val material: String,
    val tabletId: String,
    val jobId: String? = null,
    val requestedAt: String
)

class HiddenMaterialsRequestStore(private val baseDir: File) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** Atomically writes this tablet's own request file (temp + ATOMIC_MOVE, see AtomicFileWriter). */
    fun writeRequest(
        mode: HiddenMaterialsMode,
        action: String,
        scope: String,
        jobId: String?,
        docType: String,
        material: String,
        tabletId: String,
        requestedAt: String
    ) {
        val payload = HiddenMaterialsRequest(
            mode = mode.name,
            action = action,
            scope = scope,
            docType = docType,
            material = material,
            tabletId = tabletId,
            jobId = jobId,
            requestedAt = requestedAt
        )
        val dir = if (scope == "global") {
            hiddenMaterialsGlobalPath(baseDir, mode).parentFile!!
        } else {
            requireNotNull(jobId) { "job scope requires jobId" }
            hiddenMaterialsJobPath(baseDir, mode, jobId).parentFile!!
        }
        val dest = File(dir, "hidden_materials_request.$tabletId.json")
        atomicWriteFile(dest, gson.toJson(payload))
    }
}
