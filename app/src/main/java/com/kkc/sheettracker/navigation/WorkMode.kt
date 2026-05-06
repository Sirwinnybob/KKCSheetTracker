package com.kkc.sheettracker.navigation

enum class WorkMode {
    CNC,
    HARDWOODS;

    companion object {
        fun fromStored(value: String?): WorkMode {
            return runCatching { value?.let { WorkMode.valueOf(it) } }.getOrNull() ?: CNC
        }
    }
}
