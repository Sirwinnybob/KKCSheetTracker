package com.kkc.sheettracker.data.cnc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/** "Reload this mix in the CNC Controller" for one material on one machine. */
data class MixReloadReminder(val id: String, val machineId: String, val machine: String, val job: String, val material: String)

private fun String.programKey(): String =
    substringAfterLast('\\').substringAfterLast('/').trim().replace(Regex("\\.pgm$", RegexOption.IGNORE_CASE), "").lowercase(Locale.ROOT)

/**
 * True when any of the material's programs is in the CNC controller's loaded mix, or when either side is
 * unknown: the controller runs its own cached copies, so a missed reload is the expensive mistake.
 */
internal fun needsMixReload(loadedPrograms: List<String>?, materialPrograms: Set<String>?): Boolean {
    if (loadedPrograms == null || materialPrograms.isNullOrEmpty()) return true
    val material = materialPrograms.map { it.programKey() }.toSet()
    return loadedPrograms.any { it.programKey() in material }
}

/**
 * App-scoped: after the tablet changes a material's files on a machine whose controller caches its loaded
 * mix (Blue SCM's Maestro), asks that machine which programs it has loaded and, if the material is among
 * them, raises a reminder to reload the mix. Other machines read their files directly and get none.
 */
class MixReloadReminders(
    private val scope: CoroutineScope,
    /** The machine's state reader, or null for a machine with no cached loaded mix. */
    private val loadedMixSource: (machineId: String) -> MachineStateSource?,
    /** Program names the tablet knows for this material on this machine; null or empty when unknown. */
    private val materialPrograms: (machineId: String, job: String, material: String) -> Set<String>?,
    private val machineName: (String) -> String = { id -> CncMachines.all.firstOrNull { it.id == id }?.displayName ?: id },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutable = MutableStateFlow<List<MixReloadReminder>>(emptyList())
    val reminders: StateFlow<List<MixReloadReminder>> = mutable.asStateFlow()

    /** Call when a change on [machineId] may have touched the material's files, before its catalog is refreshed away. */
    fun filesChanged(machineId: String, job: String, material: String) {
        val source = loadedMixSource(machineId) ?: return
        val programs = materialPrograms(machineId, job, material)
        scope.launch {
            val loaded = try { source.fetch().getOrNull()?.maestroLoadedPrograms }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (!needsMixReload(loaded, programs)) return@launch
            mutable.update { current ->
                if (current.any { it.machineId == machineId && it.job == job && it.material == material }) current
                else current + MixReloadReminder(newId(), machineId, machineName(machineId), job, material)
            }
        }
    }

    fun dismiss(id: String) = mutable.update { current -> current.filterNot { it.id == id } }
}
