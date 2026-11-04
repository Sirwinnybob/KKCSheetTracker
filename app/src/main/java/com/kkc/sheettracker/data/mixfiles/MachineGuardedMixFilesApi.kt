package com.kkc.sheettracker.data.mixfiles

import com.kkc.sheettracker.data.cnc.CncCapability
import com.kkc.sheettracker.data.cnc.CncMachine
import com.kkc.sheettracker.data.cnc.MixServiceStatus
import com.kkc.sheettracker.data.cnc.machineAccess
import kotlinx.coroutines.CancellationException

/**
 * The mutation boundary for one explicit machine. Reads pass through; every mutation first re-reads
 * `/status` and is refused, without a POST, when the service is not the expected machine, is read-only,
 * or lacks the capability. FRZ work is refused locally on machines without FRZ support, so restored
 * drafts or deep links cannot reach a Blue SCM FRZ route.
 */
class MachineGuardedMixFilesApi(
    private val machine: CncMachine,
    private val delegate: MixFilesApi,
    private val status: suspend () -> MixServiceStatus?,
) : MixFilesApi by delegate {
    override suspend fun write(job: String, material: String, file: String, expectedRevision: String?, entries: List<EntrySpec>, completion: Completion?) =
        guarded(completion != null || isFrz(file)) { delegate.write(job, material, file, expectedRevision, entries, completion) }

    override suspend fun frz(job: String, material: String, file: String, expectedRevision: String, completion: Completion, pending: List<EntrySpec>) =
        guarded(true) { delegate.frz(job, material, file, expectedRevision, completion, pending) }

    override suspend fun rename(job: String, material: String, file: String, newFile: String, expectedRevision: String) =
        guarded(isFrz(file) || isFrz(newFile)) { delegate.rename(job, material, file, newFile, expectedRevision) }

    override suspend fun delete(job: String, material: String, file: String, expectedRevision: String) =
        guarded(isFrz(file)) { delegate.delete(job, material, file, expectedRevision) }

    override suspend fun deleteRevision(job: String, material: String, file: String, expectedRevision: String) =
        guarded(isFrz(file)) { delegate.deleteRevision(job, material, file, expectedRevision) }

    override suspend fun restore(job: String, material: String, revisionFile: String, targetFile: String, expectedTargetRevision: String?, completion: Completion?) =
        guarded(completion != null || isFrz(revisionFile) || isFrz(targetFile)) {
            delegate.restore(job, material, revisionFile, targetFile, expectedTargetRevision, completion)
        }

    override suspend fun split(job: String, material: String, file: String, expectedRevision: String, entries: List<EntrySpec>, newMixes: List<NewMix>, completion: Completion?) =
        guarded(completion != null || isFrz(file)) { delegate.split(job, material, file, expectedRevision, entries, newMixes, completion) }

    private suspend fun guarded(frz: Boolean, submit: suspend () -> MixFilesResult<MixFilesCatalog>): MixFilesResult<MixFilesCatalog> {
        if (CncCapability.MIX_FILES !in machine.capabilities) return refused("mix_unsupported", "${machine.displayName} can't edit mixes from the tablet.")
        if (frz && CncCapability.FRZ_EDITING !in machine.capabilities) return refused("frz_unsupported", frzUnsupported(machine))
        val current = try { status() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        val access = machineAccess(machine, current, requireStatus = true)
        access.reason?.let { return refused(access.code ?: "machine_unverified", it) }
        if (!access.mixFiles) return refused("mix_unsupported", "${machine.displayName}'s mix service doesn't allow mix edits.")
        if (frz && !access.frz) return refused("frz_unsupported", frzUnsupported(machine))
        return submit()
    }

    private fun refused(code: String, message: String) =
        MixFilesResult.Rejected(MixFilesError(0, code, message, null, emptyList(), null, null, null, null))
}

fun isFrz(file: String): Boolean = file.endsWith(".frz", ignoreCase = true)
fun frzUnsupported(machine: CncMachine): String = "${machine.displayName} can't edit interrupted (FRZ) mixes. This mix is view-only."
