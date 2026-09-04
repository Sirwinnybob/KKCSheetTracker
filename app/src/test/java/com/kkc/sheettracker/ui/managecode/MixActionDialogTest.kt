package com.kkc.sheettracker.ui.managecode

import com.kkc.sheettracker.data.mixservice.MixCatalogEntry
import com.kkc.sheettracker.data.mixservice.MixCatalogMutationResult
import com.kkc.sheettracker.data.mixservice.MixCatalogSnapshot
import com.kkc.sheettracker.data.mixservice.MixGenerationTarget
import com.kkc.sheettracker.data.mixservice.MixLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixActionDialogTest {
    @Test
    fun `empty catalog offers one tap first default generation`() {
        val content = mixActionDialogContent(catalog())

        assertEquals(MixGenerationTarget.FirstDefault, content.automaticTarget)
        assertTrue(content.activeMixes.isEmpty())
        assertFalse(content.generationBlockedByExternal)
    }

    @Test
    fun `history collision prompts for a distinct name instead of offering a blocked default`() {
        val snapshot = catalog(history("19mmMix"))

        assertNull(mixActionDialogContent(snapshot).automaticTarget)
        assertFalse(isAdditionalMixNameReady("19mmMix", "19mmMix", snapshot))
        assertTrue(isAdditionalMixNameReady("19mmMix", "19mmMix 2", snapshot))
    }

    @Test
    fun `duplicate catalog name feedback explains service wide case insensitive uniqueness`() {
        val message = mixMutationErrorMessage(MixCatalogMutationResult.DuplicateName("New Mix"))

        assertTrue(message.contains("New Mix"))
        assertTrue(message.contains("case-insensitive"))
        assertTrue(message.contains("all definitions"))
    }

    @Test
    fun `active mixes are selectable while history remains display only`() {
        val content = mixActionDialogContent(catalog(active("Current"), history("Old")))

        assertNull(content.automaticTarget)
        assertEquals(listOf("Current"), content.activeMixes.map { it.name })
        assertEquals(listOf("Old"), content.historyMixes.map { it.name })
        assertFalse(content.generationBlockedByExternal)
    }

    @Test
    fun `active replacement selection captures its revision and saved programs as the baseline`() {
        val entry = active("Current").copy(programs = listOf("R2.pgm", "R1.pgm"))
        val snapshot = catalog(entry)

        assertEquals(
            MixGenerationTarget.ReplaceActive("Current", 7L, listOf("R2.pgm", "R1.pgm")),
            replacementTarget(entry, snapshot)
        )
    }

    @Test
    fun `create another stays disabled until the prefilled name changes to a valid unique name`() {
        val snapshot = catalog(active("Current"))

        assertFalse(isAdditionalMixNameReady(originalName = "Current", draft = "Current", catalog = snapshot))
        assertFalse(isAdditionalMixNameReady(originalName = "Current", draft = "Current!", catalog = snapshot))
        assertFalse(isAdditionalMixNameReady(originalName = "Current", draft = "Current 2", catalog = catalog(active("Current 2"))))
        assertFalse(isAdditionalMixNameReady(originalName = "Current", draft = "   ", catalog = snapshot))
        assertFalse(isAdditionalMixNameReady(originalName = "Current", draft = " Current 2 ", catalog = snapshot))
        assertFalse(isAdditionalMixNameReady(originalName = "Current", draft = "CON", catalog = snapshot))
        assertTrue(isAdditionalMixNameReady(originalName = "Current", draft = "Current 2", catalog = snapshot))
    }

    @Test
    fun `external block permits permanent deletion confirmation only for its exact filename`() {
        val snapshot = catalog(external("Manual.mix"))
        val content = mixActionDialogContent(snapshot)

        assertTrue(content.generationBlockedByExternal)
        assertNull(externalDeletionFilename(snapshot, "manual.mix"))
        assertNull(externalDeletionFilename(snapshot, "Manual.mix.bak"))
        assertEquals("Manual.mix", externalDeletionFilename(snapshot, "Manual.mix"))
    }

    private fun catalog(vararg entries: MixCatalogEntry) = MixCatalogSnapshot(
        job = "100 - Alpha",
        material = "19mm",
        revision = 7L,
        entries = entries.toList()
    )

    private fun active(name: String) = MixCatalogEntry(
        name = name,
        mixFilename = "$name.mix",
        lifecycle = MixLifecycle.ACTIVE,
        programs = listOf("R1.pgm")
    )

    private fun history(name: String) = MixCatalogEntry(
        name = name,
        mixFilename = "$name.mix",
        lifecycle = MixLifecycle.HISTORY
    )

    private fun external(filename: String) = MixCatalogEntry(
        name = filename,
        mixFilename = filename,
        lifecycle = MixLifecycle.EXTERNAL
    )
}
