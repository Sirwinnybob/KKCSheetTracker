package com.kkc.sheettracker.data

import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers TimecardBgStore.deleteOrphanedMedia directly -- it's a pure function (no Context /
 * DataStore needed), so this exercises the orphan-cleanup logic without Robolectric.
 */
class TimecardBgStoreTest {

    private fun tempMediaDir(): File =
        createTempDirectory("timecard-bg-${UUID.randomUUID()}").toFile().also { it.deleteOnExit() }

    @Test
    fun `deletes previous media file when replaced by a different selection`() {
        val mediaDir = tempMediaDir()
        val oldFile = File(mediaDir, "bg_image_1").apply { writeText("old") }
        val newFile = File(mediaDir, "bg_image_2").apply { writeText("new") }

        val deleted = TimecardBgStore.deleteOrphanedMedia(mediaDir, oldFile.absolutePath, newFile.absolutePath)

        assertTrue(deleted)
        assertFalse(oldFile.exists())
        assertTrue(newFile.exists())
    }

    @Test
    fun `deletes previous media file when cleared to no media`() {
        val mediaDir = tempMediaDir()
        val oldFile = File(mediaDir, "bg_video_1").apply { writeText("old") }

        val deleted = TimecardBgStore.deleteOrphanedMedia(mediaDir, oldFile.absolutePath, null)

        assertTrue(deleted)
        assertFalse(oldFile.exists())
    }

    @Test
    fun `does not delete when the same file is still selected`() {
        val mediaDir = tempMediaDir()
        val file = File(mediaDir, "bg_image_1").apply { writeText("keep") }

        val deleted = TimecardBgStore.deleteOrphanedMedia(mediaDir, file.absolutePath, file.absolutePath)

        assertFalse(deleted)
        assertTrue(file.exists())
    }

    @Test
    fun `does not delete a path outside the background media directory`() {
        val mediaDir = tempMediaDir()
        val outsideDir = tempMediaDir()
        val outsideFile = File(outsideDir, "not_ours").apply { writeText("untouched") }

        val deleted = TimecardBgStore.deleteOrphanedMedia(mediaDir, outsideFile.absolutePath, null)

        assertFalse(deleted)
        assertTrue(outsideFile.exists())
    }

    @Test
    fun `no-op when there was no previous media`() {
        val mediaDir = tempMediaDir()

        val deleted = TimecardBgStore.deleteOrphanedMedia(mediaDir, null, "${mediaDir.absolutePath}/whatever")

        assertFalse(deleted)
    }
}
