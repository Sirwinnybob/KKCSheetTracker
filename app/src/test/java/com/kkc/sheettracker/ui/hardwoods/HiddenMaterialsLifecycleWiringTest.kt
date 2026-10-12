package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMaterialsLifecycleWiringTest {
    @Test
    fun replacementEffectOwnsANewBindingAndClient() {
        val source = SourceFiles.mainSource(
            "com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt"
        ).readText()
        val owner = source.indexOf("val hiddenMaterialsLifecycleOwner = LocalLifecycleOwner.current")
        val effectBody = source.indexOf("val sourceToken = hiddenMaterialsLifecycleGate.bindSource()", owner)
        val binding = source.indexOf("HiddenMaterialsClientBinding(hiddenMaterialsLifecycleGate)")
        val client = source.indexOf("HiddenMaterialsLiveClient(", owner)
        val observer = source.indexOf("val observer = LifecycleEventObserver", effectBody)

        // Replacing an owner/config/store reruns the effect. Reusing a remembered binding here
        // would claim it with a different source token and reproduce the production crash.
        assertTrue("Binding must be created by each effect", binding in effectBody until observer)
        assertTrue("Live client must capture that effect's binding", client in binding until observer)
        val effectKeys = source.substring(source.indexOf("DisposableEffect(", owner), effectBody)
        listOf("hiddenMaterialsLifecycleOwner", "adminSyncConfig", "hiddenMaterialsStore",
            "hiddenMaterialsTabletId", "pdfMarkupReadOnly").forEach { key ->
            assertTrue("Changes to $key must replace the effect", effectKeys.contains(key))
        }
    }
}
