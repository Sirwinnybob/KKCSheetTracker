package com.kkc.sheettracker.perf

/** CPU time of one thread, from a /proc/<pid>/task/<tid>/stat line. */
data class ThreadTicks(val name: String, val ticks: Long)

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

    /** Thread name (comm, at most 15 chars) plus its CPU ticks, or null if malformed. */
    fun parseThread(stat: String): ThreadTicks? {
        val open = stat.indexOf('(')
        val close = stat.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val ticks = parseCpuTicks(stat) ?: return null
        return ThreadTicks(stat.substring(open + 1, close), ticks)
    }

    /** Resident set size in bytes from the contents of /proc/self/status, or null. */
    fun parseRssBytes(status: String): Long? {
        val line = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return null
        val kb = line.removePrefix("VmRSS:").trim().substringBefore(' ').toLongOrNull() ?: return null
        return kb * 1024L
    }
}
