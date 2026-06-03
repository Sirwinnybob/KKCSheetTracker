package com.kkc.sheettracker.navigation

enum class WorkMode {
    CNC,
    HARDWOODS,
    ASSEMBLY,
    SPECIALTY;

    companion object {
        fun fromStored(value: String?): WorkMode {
            val normalized = value?.trim()?.uppercase()
            return runCatching { normalized?.let { WorkMode.valueOf(it) } }.getOrNull() ?: CNC
        }
    }
}
