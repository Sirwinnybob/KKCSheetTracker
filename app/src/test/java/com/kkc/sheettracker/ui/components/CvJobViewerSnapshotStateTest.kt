package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.CvJobWings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CvJobViewerSnapshotStateTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun snapshot_readsOneCanonicalPayload_forDoorsAndWings() {
        val job = temporaryFolder.newFolder("688 - TEST")
        val target = File(job, ".metadata/cv_job").apply { mkdirs() }
        val fixture = requireNotNull(javaClass.classLoader?.getResource("cvjob/verified-wing-export.json")).readText()
        File(target, "job_data.json").writeText(fixture)

        val snapshot = loadCvJobViewerSnapshot(temporaryFolder.root.absolutePath, job.name)

        assertTrue(snapshot.doors is CvJobDoorPreviews.Loaded)
        assertTrue(snapshot.wings is CvJobWings.Loaded)
    }
}
