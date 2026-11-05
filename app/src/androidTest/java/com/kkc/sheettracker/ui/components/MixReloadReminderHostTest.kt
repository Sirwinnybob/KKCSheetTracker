package com.kkc.sheettracker.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.kkc.sheettracker.data.cnc.CncMachines
import com.kkc.sheettracker.data.cnc.MachineState
import com.kkc.sheettracker.data.cnc.MixReloadReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MixReloadReminderHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reminderShowsUntilOkIsTapped() {
        val reminders = MixReloadReminders(CoroutineScope(Dispatchers.Unconfined),
            loadedMixSource = { { Result.success(MachineState(maestroLoadedPrograms = listOf("R340305Z"))) } },
            materialPrograms = { _, _, _ -> setOf("R340305Z.pgm") })
        compose.setContent { MaterialTheme { MixReloadReminderHost(reminders) } }
        reminders.filesChanged(CncMachines.scmNew.id, "715 - PRODEN", "1_2 Baltic Birch")
        compose.onNodeWithText(MIX_RELOAD_TITLE).assertIsDisplayed()
        compose.onNodeWithText("Blue SCM still has the old 1_2 Baltic Birch files loaded", substring = true).assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText(MIX_RELOAD_TITLE).assertDoesNotExist()
        assertTrue(reminders.reminders.value.isEmpty())
    }
}
