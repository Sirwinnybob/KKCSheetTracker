package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import java.io.File
import java.time.Instant

/**
 * One queued edit to a job's board_position.json entry — a label assignment, a board-section
 * (active/pending delivery) change, or both.
 */
data class JobBoardEdit(
    val folderName: String,
    val labelIds: List<Int>? = null,
    val boardSection: Int? = null
)

data class JobBoardEditRequest(
    val edits: List<JobBoardEdit>,
    val tabletId: String,
    val requestedAt: String
)

/**
 * The tablet's queued label/pending-delivery edits. The Hours Tracker backend polls for
 * `job_board_request.json` (beside `job_board.json`), applies each edit to the master
 * `job_board.json`, then deletes the request. Multiple edits queued before the next poll are
 * merged by folderName rather than overwritten, since an admin may tag several jobs within one
 * poll window.
 */
class JobBoardRequestStore(private val baseDir: File) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun queueLabelEdit(folderName: String, labelIds: List<Int>, tabletId: String) {
        queueEdit(folderName, tabletId) { it.copy(labelIds = labelIds) }
    }

    fun queueBoardSectionEdit(folderName: String, boardSection: Int, tabletId: String) {
        queueEdit(folderName, tabletId) { it.copy(boardSection = boardSection) }
    }

    private fun queueEdit(folderName: String, tabletId: String, apply: (JobBoardEdit) -> JobBoardEdit) {
        val dest = File(baseDir, "job_board_request.json")
        val existing = readExisting(dest)
        val existingEdit = existing.find { it.folderName == folderName } ?: JobBoardEdit(folderName)
        val updatedEdit = apply(existingEdit)
        val edits = existing.filterNot { it.folderName == folderName } + updatedEdit

        val payload = JobBoardEditRequest(
            edits = edits,
            tabletId = tabletId,
            requestedAt = Instant.now().toString()
        )
        val tmp = File(baseDir, "job_board_request.json.tmp")
        tmp.writeText(gson.toJson(payload))
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun readExisting(file: File): List<JobBoardEdit> {
        if (!file.exists()) return emptyList()
        return try {
            gson.fromJson(file.readText(), JobBoardEditRequest::class.java)?.edits ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
