package com.kkc.sheettracker.data

import java.util.Locale

internal data class ParsedJobFolderName(
    val jobNumber: String,
    val jobName: String
)

private data class ParsedJobNumber(
    val major: Int,
    val alpha: String,
    val revision: Int?
)

private val jobFolderPattern = Regex("""^(\d+[A-Za-z]*(?:-\d+)?)\s*-\s*(.+)$""")
private val jobNumberPattern = Regex("""^(\d+)([A-Za-z]*)(?:-(\d+))?$""")

internal fun parseJobFolderName(folderName: String): ParsedJobFolderName? {
    val match = jobFolderPattern.find(folderName.trim()) ?: return null
    val jobNumber = match.groupValues[1].trim()
    val jobName = match.groupValues[2].trim()
    if (jobNumber.isBlank() || jobName.isBlank()) return null
    return ParsedJobFolderName(jobNumber = jobNumber, jobName = jobName)
}

internal fun compareJobNumbersDesc(left: String, right: String): Int {
    val leftParsed = parseJobNumber(left)
    val rightParsed = parseJobNumber(right)

    if (leftParsed != null && rightParsed != null) {
        val majorCmp = rightParsed.major.compareTo(leftParsed.major)
        if (majorCmp != 0) return majorCmp

        val leftAlpha = leftParsed.alpha.uppercase(Locale.US)
        val rightAlpha = rightParsed.alpha.uppercase(Locale.US)
        val alphaCmp = when {
            leftAlpha == rightAlpha -> 0
            leftAlpha.isEmpty() -> -1
            rightAlpha.isEmpty() -> 1
            else -> leftAlpha.compareTo(rightAlpha)
        }
        if (alphaCmp != 0) return alphaCmp

        val revisionCmp = when {
            leftParsed.revision == rightParsed.revision -> 0
            leftParsed.revision == null -> -1
            rightParsed.revision == null -> 1
            else -> leftParsed.revision.compareTo(rightParsed.revision)
        }
        if (revisionCmp != 0) return revisionCmp

        return left.compareTo(right, ignoreCase = true)
    }

    if (leftParsed != null) return -1
    if (rightParsed != null) return 1
    return right.compareTo(left, ignoreCase = true)
}

private fun parseJobNumber(value: String): ParsedJobNumber? {
    val match = jobNumberPattern.find(value.trim()) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val alpha = match.groupValues[2]
    val revision = match.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull()
    return ParsedJobNumber(major = major, alpha = alpha, revision = revision)
}
