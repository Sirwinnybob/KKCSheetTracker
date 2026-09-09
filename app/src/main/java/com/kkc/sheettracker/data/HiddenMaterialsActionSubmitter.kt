package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fires both write paths for one hide/unhide action, per the dual-write requirement in the
 * 2026-09-08 hidden-materials design doc: the durable sidecar is always written first (it alone
 * guarantees eventual delivery), then the REST fast path is attempted on a best-effort basis --
 * AdminSyncClient.applyHiddenMaterialsAction already swallows network/server failures internally,
 * so no error handling is needed here for that half. Both halves are best-effort: a transient
 * sidecar write failure (VPN blip, disk-full, permission error on the shared network drive) is
 * swallowed the same way, so a hide/unhide tap never crashes the caller's coroutine.
 */
suspend fun submitHiddenMaterialsAction(
    serverUrl: String?,
    requestStore: HiddenMaterialsRequestStore,
    mode: HiddenMaterialsMode,
    action: String,
    scope: String,
    jobId: String?,
    docType: String,
    material: String,
    tabletId: String,
    requestedAt: String
) {
    withContext(Dispatchers.IO) {
        runCatching {
            requestStore.writeRequest(
                mode = mode,
                action = action,
                scope = scope,
                jobId = jobId,
                docType = docType,
                material = material,
                tabletId = tabletId,
                requestedAt = requestedAt
            )
        }
        if (serverUrl != null) {
            AdminSyncClient(serverUrl).applyHiddenMaterialsAction(
                mode = mode,
                action = action,
                scope = scope,
                docType = docType,
                material = material,
                tabletId = tabletId,
                jobId = jobId,
                requestedAt = requestedAt
            )
        }
    }
}
