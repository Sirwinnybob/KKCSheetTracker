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

fun WorkMode.displayName(): String = when (this) {
    WorkMode.CNC -> "CNC"
    WorkMode.HARDWOODS -> "Hardwoods"
    WorkMode.ASSEMBLY -> "Assembly"
    WorkMode.SPECIALTY -> "Specialty"
}

fun WorkMode.shortLabel(): String = when (this) {
    WorkMode.CNC -> "CNC"
    WorkMode.HARDWOODS -> "HW"
    WorkMode.ASSEMBLY -> "ASM"
    WorkMode.SPECIALTY -> "SPC"
}
