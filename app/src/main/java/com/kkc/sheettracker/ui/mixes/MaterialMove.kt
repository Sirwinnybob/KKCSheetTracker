package com.kkc.sheettracker.ui.mixes

import com.kkc.sheettracker.data.cnc.CncMachine
import com.kkc.sheettracker.data.cnc.MaterialTransferRecord
import com.kkc.sheettracker.data.cnc.MixServiceStatus
import com.kkc.sheettracker.data.cnc.PunloadPolicy
import com.kkc.sheettracker.data.cnc.SheetProgressEntry
import com.kkc.sheettracker.data.cnc.SheetProgressSnapshot
import com.kkc.sheettracker.data.cnc.TransferState
import com.kkc.sheettracker.data.cnc.machineAccess

/** Everything a drop or a "Move to…" choice is checked against, read at the moment of the gesture. */
data class MoveContext(
    val source: CncMachine,
    val destination: CncMachine,
    val material: String,
    val sourceStatus: MixServiceStatus?,
    val destinationStatus: MixServiceStatus?,
    /** Unsaved MIX or second-pass edits for this material. They are never applied implicitly. */
    val dirty: Boolean,
    val activeTransfer: Boolean,
    val sourceCatalogLive: Boolean,
    val destinationHasMaterial: Boolean,
)

enum class PunloadChoice { RESTORE, KEEP_REMOVED, CANCEL }

sealed interface MovePlan {
    data class Blocked(val reason: String) : MovePlan
    /** Returning to a machine that keeps PUNLOAD: the operator chooses before anything is sent. */
    data class AskPunload(val source: CncMachine, val destination: CncMachine) : MovePlan {
        val question: String get() = "Restore PUNLOAD for ${destination.displayName}?"
        /** Cancel returns null: no preview, no request, no file change. */
        fun choose(choice: PunloadChoice): Prepare? = when (choice) {
            PunloadChoice.RESTORE -> Prepare(source, destination, PunloadPolicy.RESTORE)
            PunloadChoice.KEEP_REMOVED -> Prepare(source, destination, PunloadPolicy.KEEP_REMOVED)
            PunloadChoice.CANCEL -> null
        }
    }
    data class Prepare(val source: CncMachine, val destination: CncMachine, val policy: PunloadPolicy) : MovePlan {
        val notice: String get() = moveNotice(destination.displayName, policy)
    }
}

internal fun moveNotice(destination: String, policy: PunloadPolicy): String = "Moving to $destination · " + when (policy) {
    PunloadPolicy.REMOVE -> "PUNLOAD will be removed"
    PunloadPolicy.RESTORE -> "PUNLOAD will be restored"
    PunloadPolicy.KEEP_REMOVED -> "PUNLOAD stays removed"
}

fun planMaterialMove(context: MoveContext): MovePlan {
    val source = context.source
    val destination = context.destination
    if (source.id == destination.id) return MovePlan.Blocked("${context.material} is already on ${source.displayName}.")
    if (context.activeTransfer) return MovePlan.Blocked("A move of ${context.material} is already running. Checking move status.")
    if (context.dirty) return MovePlan.Blocked("Apply or discard the mix and second-pass changes for ${context.material} before moving it.")
    for ((machine, status) in listOf(source to context.sourceStatus, destination to context.destinationStatus)) {
        if (status == null) return MovePlan.Blocked("${machine.displayName} is offline. Moving a material needs both machines online.")
        val access = machineAccess(machine, status, requireStatus = true)
        access.reason?.let { return MovePlan.Blocked(it) }
    }
    val unsupported = listOf(source to context.sourceStatus, destination to context.destinationStatus)
        .filterNot { (machine, status) -> machineAccess(machine, status, requireStatus = true).moveMaterial }
        .map { it.first.displayName }
    if (unsupported.isNotEmpty()) return MovePlan.Blocked("Moving materials needs a CNC service update on ${unsupported.joinToString(" and ")}.")
    if (!context.sourceCatalogLive) return MovePlan.Blocked("Connect to ${source.displayName} and refresh before moving ${context.material}.")
    if (context.destinationHasMaterial) return MovePlan.Blocked("${destination.displayName} already has ${context.material}. Moves never overwrite a material.")
    return if (destination.forcesPUnloadRemoval) MovePlan.Prepare(source, destination, PunloadPolicy.REMOVE)
    else MovePlan.AskPunload(source, destination)
}

/** Read-only copy of canonical progress for the preview. Nothing here writes or derives a status. */
fun transferProgressSnapshot(job: String, input: MaterialMixesInput, capturedAtMs: Long): SheetProgressSnapshot =
    SheetProgressSnapshot(1, capturedAtMs, input.pages.sortedBy { it.pageNumber }.map { page ->
        SheetProgressEntry(job, input.material.pdfFilename, page.pageNumber, input.material.fileFingerprint,
            input.statusOf(page.pageNumber).name, page.pgmFiles)
    })

/** Tile label for a move whose source tile is still shown in its source group. */
internal fun transferLabel(record: MaterialTransferRecord, destinationName: String): String? = when (record.state) {
    TransferState.SUBMITTING, TransferState.SUBMITTED ->
        moveNotice(destinationName, PunloadPolicy.entries.firstOrNull { it.wire == record.punloadPolicy } ?: PunloadPolicy.REMOVE)
    TransferState.UNCERTAIN -> "Checking move status"
    TransferState.FAILED -> "Move didn't finish" + (record.message?.let { ": $it" } ?: "")
    TransferState.COMPLETED -> null
}
