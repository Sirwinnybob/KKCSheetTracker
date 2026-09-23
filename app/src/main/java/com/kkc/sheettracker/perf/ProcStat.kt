package com.kkc.sheettracker.perf

/** Parsing for /proc/<pid>/stat and /proc/<pid>/task/<tid>/stat lines. */
object ProcStat {
    /**
     * utime + stime in clock ticks, or null if the line is malformed. The comm field is wrapped in
     * parentheses and may itself contain spaces and parentheses, so fields are counted from the
     * last ')'.
     */
    fun parseCpuTicks(stat: String): Long? {
        val afterComm = stat.substringAfterLast(')', missingDelimiterValue = "").trim()
        val fields = afterComm.split(' ')
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }
}
