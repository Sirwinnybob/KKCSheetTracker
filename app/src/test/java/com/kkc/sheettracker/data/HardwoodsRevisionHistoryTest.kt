package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HardwoodRevisionHistory
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwoodsRevisionHistoryTest {

    @Test
    fun loadHardwoodsRevisionHistory_parsesRevisionFile() {
        val root = Files.createTempDirectory("hardwoods-history-test").toFile()
        try {
            val job = File(root, "998 - TEST")
            val metadata = File(job, ".metadata/hardwoods")
            metadata.mkdirs()
            writeDeploymentGate(job)
            File(metadata, "cutlist_revisions.json").writeText(
                """
                {
                  "schemaVersion": 1,
                  "updatedAt": "2026-05-11T12:00:00Z",
                  "currentRevision": 3,
                  "revisions": [
                    {"revision": 1, "kind": "SNAPSHOT", "added": [], "removed": [], "modified": []},
                    {"revision": 2, "kind": "DIFF", "added": [], "removed": [], "modified": []},
                    {"revision": 3, "kind": "DIFF", "added": [], "removed": [], "modified": []}
                  ],
                  "currentRowStates": [
                    {"docType": "FACE_FRAME_CUT_LIST", "rowId": "abc", "latestRevision": 3, "changedPendingRecut": true}
                  ]
                }
                """.trimIndent()
            )

            val repo = HardwoodsRepository(root)
            val history: HardwoodRevisionHistory? = repo.loadHardwoodsRevisionHistory(job.name)
            assertNotNull(history)
            assertEquals(3, history!!.currentRevision)
            assertEquals(3, history.revisions.size)
            assertEquals(1, history.currentRowStates.size)
            assertTrue(history.currentRowStates[0].changedPendingRecut)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun getRowRevisionStates_returnsKeyedMap() {
        val root = Files.createTempDirectory("hardwoods-history-map-test").toFile()
        try {
            val job = File(root, "998 - TEST")
            val metadata = File(job, ".metadata/hardwoods")
            metadata.mkdirs()
            writeDeploymentGate(job)
            File(metadata, "cutlist_revisions.json").writeText(
                """
                {
                  "schemaVersion": 1,
                  "currentRevision": 2,
                  "revisions": [],
                  "currentRowStates": [
                    {"docType": "FACE_FRAME_CUT_LIST", "rowId": "row-1", "latestRevision": 2, "changedPendingRecut": false},
                    {"docType": "NAILER_CUT_LIST", "rowId": "row-2", "latestRevision": 2, "changedPendingRecut": true}
                  ]
                }
                """.trimIndent()
            )

            val repo = HardwoodsRepository(root)
            val states = repo.getRowRevisionStates(job.name)
            assertEquals(2, states.size)
            assertEquals(false, states["FACE_FRAME_CUT_LIST" to "row-1"]?.changedPendingRecut)
            assertEquals(true, states["NAILER_CUT_LIST" to "row-2"]?.changedPendingRecut)
            assertEquals(2, states["NAILER_CUT_LIST" to "row-2"]?.latestRevision)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeDeploymentGate(jobDir: File) {
        val metadataDir = File(jobDir, ".metadata").apply { mkdirs() }
        File(metadataDir, "deployment_gate.json").writeText("""{"deployed": true}""")
    }
}
