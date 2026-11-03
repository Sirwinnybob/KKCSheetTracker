package com.kkc.sheettracker.data.cnc

import com.kkc.sheettracker.data.mixservice.MIX_SERVICE_BASE_URL

enum class CncCapability { MIX_FILES, FRZ_EDITING, SECOND_PASS, MOVE_MATERIAL }

data class CncMachine(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val capabilities: Set<CncCapability>,
)

object CncMachines {
    val scm = CncMachine(
        id = "scm",
        displayName = "Red SCM",
        baseUrl = MIX_SERVICE_BASE_URL,
        capabilities = setOf(CncCapability.MIX_FILES, CncCapability.FRZ_EDITING, CncCapability.SECOND_PASS),
    )
    val scmNew = CncMachine(
        id = "scm-new",
        displayName = "Blue SCM",
        baseUrl = "http://192.168.20.101:8477",
        capabilities = emptySet(),
    )
    val all: List<CncMachine> = listOf(scm, scmNew)
}
