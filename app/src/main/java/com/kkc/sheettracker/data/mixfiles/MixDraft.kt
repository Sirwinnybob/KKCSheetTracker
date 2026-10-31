package com.kkc.sheettracker.data.mixfiles

import java.util.Locale

data class DraftEntry(
    val id: Long,
    val source: MixFileEntry?,
    val newPgm: String?,
    val quantity: Int,
    val remaining: Int?,
    val parameters: LinkedHashMap<String, String>?,
)

data class MixDraft(
    val groupKey: String,
    val baseMixRevision: String?,
    val baseFrzRevision: String?,
    val entries: List<DraftEntry>,
    val newFile: String?,
    val splitOff: List<NewMixDraft>,
    val changeCount: Int,
    // Completion comes from tablet sheet statuses, never from FRZ remaining counts.
    val completedPgms: Set<String> = emptySet(),
    val baselineEntries: List<DraftEntry> = entries,
    val nextEntryId: Long = (entries.maxOfOrNull { it.id } ?: 0L) + 1,
) {
    val isInterrupted: Boolean get() = baseFrzRevision != null
}

data class NewMixDraft(val file: String, val entries: List<DraftEntry>)
data class RebaseResult(val draft: MixDraft, val dropped: List<String>)

object MixDrafts {
    fun start(group: MixGroup, completedPgms: List<String> = emptyList()): MixDraft {
        require(group.mix != null || group.frz != null) { "An active mix or FRZ is required" }
        val locks = completedPgms.map { it.key() }.toSet()
        val source = (group.frz ?: group.mix)!!.entries
        val entries = source.mapIndexed { index, entry -> entry.toDraft(index + 1L) }.let { entries ->
            if (group.isInterrupted) entries.filter { it.pgm().key() in locks } + entries.filterNot { it.pgm().key() in locks }
            else entries
        }
        return MixDraft(group.key, group.mix?.revision, group.frz?.revision, entries, null, emptyList(), 0, locks)
    }

    fun startNew(file: String, pages: List<SheetPage>, inOtherMixes: Set<String>): MixDraft {
        val excluded = inOtherMixes.map { it.key() }.toSet()
        val pgms = pages.sortedBy { it.pageNumber }.flatMap { it.pgmFiles }
            .distinctBy { it.key() }.filterNot { it.key() in excluded }
        val entries = pgms.mapIndexed { index, pgm -> DraftEntry(index + 1L, null, pgm, 1, null, null) }
        return MixDraft(file.key(), null, null, entries, file, emptyList(), 1, baselineEntries = emptyList())
    }

    fun move(d: MixDraft, fromId: Long, toIndex: Int): MixDraft {
        val entry = d.entries.find { it.id == fromId } ?: return d
        if (d.locked(entry)) return d
        // Keep completed rows in their slots even when an unlocked row crosses them.
        val movable = d.entries.filterNot { d.locked(it) }.toMutableList()
        val target = d.entries.take(toIndex.coerceIn(0, d.entries.lastIndex)).count { !d.locked(it) }
            .coerceAtMost(movable.lastIndex)
        movable.remove(entry)
        movable.add(target, entry)
        val iterator = movable.iterator()
        return changed(d, d.entries.map { if (d.locked(it)) it else iterator.next() })
    }

    fun add(d: MixDraft, pgm: String): MixDraft {
        if (pgm.key() in d.completedPgms) return d
        val entry = DraftEntry(d.nextEntryId, null, pgm, 1, if (d.isInterrupted) 1 else null, null)
        return changed(d, d.entries + entry).copy(nextEntryId = d.nextEntryId + 1)
    }

    fun remove(d: MixDraft, id: Long): MixDraft {
        val entry = d.entries.find { it.id == id } ?: return d
        if (d.isInterrupted && d.locked(entry)) return d
        return changed(d, d.entries.filterNot { it.id == id })
    }

    fun setQuantity(d: MixDraft, id: Long, quantity: Int, remaining: Int?): MixDraft {
        val entry = d.entries.find { it.id == id } ?: return d
        if (d.locked(entry)) return d
        require(entry.source != null) { "Apply the new sheet before editing its quantity or parameters" }
        require(quantity >= 1) { "Quantity must be at least 1" }
        if (quantity == entry.quantity && remaining == null) return d
        val count = if (d.isInterrupted) {
            (remaining ?: if (quantity == entry.quantity) entry.remaining ?: quantity else quantity).coerceIn(1, quantity)
        } else null
        val parameters = entry.parameters?.let { LinkedHashMap(it) }
        if (!d.isInterrupted && quantity != entry.quantity) {
            val key = parameters?.keys?.firstOrNull { it.equals("R", true) } ?: "R"
            val original = entry.source.parameters.entries.firstOrNull { it.key.equals("R", true) }
            if (quantity == entry.source.quantity) {
                if (original == null) parameters?.remove(key) else parameters?.set(key, original.value)
            } else parameters?.set(key, quantity.toString())
        }
        val updated = entry.copy(quantity = quantity, remaining = count, parameters = parameters)
        return changed(d, d.entries.map { if (it.id == id) updated else it })
    }

    fun setParameters(d: MixDraft, id: Long, parameters: LinkedHashMap<String, String>): MixDraft {
        require(!d.isInterrupted) { "Parameters cannot be edited on an FRZ" }
        val entry = d.entries.find { it.id == id } ?: return d
        if (d.locked(entry)) return d
        require(entry.source != null) { "Apply the new sheet before editing its quantity or parameters" }
        val quantity = parameters.entries.firstOrNull { it.key.equals("R", true) }?.value?.toIntOrNull() ?: 1
        require(quantity >= 1) { "Quantity must be at least 1" }
        return changed(d, d.entries.map {
            if (it.id == id) it.copy(parameters = LinkedHashMap(parameters), quantity = quantity) else it
        })
    }

    fun splitOff(d: MixDraft, ids: Set<Long>, newFile: String): MixDraft {
        val selected = d.entries.filter { it.id in ids }
        if (selected.isEmpty() || selected.any { d.locked(it) }) return d
        require(!d.isInterrupted || d.baseMixRevision != null) { "FRZ-only split is unsupported by the mix-files service" }
        require(d.newFile == null) { "Save a new mix before splitting it" }
        require(d.splitOff.none { it.file.equals(newFile, true) }) { "Split mix name is already in this draft" }
        return changed(d, d.entries.filterNot { it.id in ids }, d.splitOff + NewMixDraft(newFile, selected))
    }

    fun fromRevision(group: MixGroup, revision: MixFileRow, completedPgms: List<String> = emptyList()): MixDraft {
        require(!revision.file.endsWith(".frz", true) && revision.revisionOf?.endsWith(".frz", true) != true) {
            "FRZ revisions are view-only"
        }
        val d = start(group, completedPgms)
        if (!group.isInterrupted) {
            val entries = revision.entries.mapIndexed { index, entry -> entry.toDraft(d.nextEntryId + index) }
            return changed(d, entries).copy(nextEntryId = d.nextEntryId + entries.size)
        }
        val pending = d.entries.filterNot { d.locked(it) }.toMutableList()
        val ordered = revision.entries.mapNotNull { old ->
            val index = pending.indexOfFirst { it.pgm().equals(old.pgm, true) }
            if (index < 0) null else pending.removeAt(index)
        }
        return changed(d, d.entries.filter { d.locked(it) } + ordered + pending)
    }

    /** Reapply deltas by source occurrence, retaining fresh values the user did not edit. */
    fun rebase(d: MixDraft, fresh: MixGroup, completedPgms: List<String> = d.completedPgms.toList()): RebaseResult {
        require(d.newFile == null) { "A new mix has no existing revision to rebase" }
        val current = start(fresh, completedPgms)
        val matches = matchOccurrences(d.baselineEntries, current.entries)
        var nextId = d.nextEntryId
        val stableIds = current.entries.associate { entry ->
            val old = matches.entries.firstOrNull { it.value.id == entry.id }?.key
            entry.id to (old?.id ?: nextId++)
        }
        val baseline = current.entries.map { it.copy(id = stableIds.getValue(it.id)) }
        val byOldId = matches.mapKeys { it.key.id }.mapValues { (_, freshEntry) ->
            baseline.first { it.id == stableIds.getValue(freshEntry.id) }
        }
        val oldBaseline = d.baselineEntries.associateBy { it.id }
        val wanted = (d.entries + d.splitOff.flatMap { it.entries }).map { it.id }.toSet()
        val removed = oldBaseline.keys - wanted
        val dropped = mutableListOf<String>()
        fun reapplied(entry: DraftEntry): DraftEntry? {
            val original = oldBaseline[entry.id] ?: return entry.takeUnless { current.locked(it) }
            val updated = byOldId[entry.id] ?: run { dropped += entry.pgm(); return null }
            if (current.locked(updated)) return updated
            val quantity = if (entry.quantity != original.quantity) entry.quantity else updated.quantity
            val remaining = if (entry.remaining != original.remaining) entry.remaining else updated.remaining
            val countEdited = entry.quantity != original.quantity || entry.remaining != original.remaining
            // An untouched FRZ zero is a completion disagreement; the service resolution owns its restoration.
            val normalize = current.isInterrupted && (countEdited || remaining != 0)
            return updated.copy(
                quantity = quantity,
                remaining = if (normalize) (remaining ?: quantity).coerceIn(1, quantity) else remaining,
                parameters = mergeParameters(original.parameters, entry.parameters, updated.parameters),
            )
        }
        val desired = d.entries.mapNotNull(::reapplied)
        val splits = d.splitOff.mapNotNull { split ->
            val entries = split.entries.mapNotNull(::reapplied).filterNot { current.locked(it) }
            if (entries.isEmpty()) null else NewMixDraft(split.file, entries)
        }
        val splitIds = splits.flatMap { it.entries }.map { it.id }.toSet()
        val edits = desired.associateBy { it.id }
        var entries = baseline.filterNot {
            it.id in splitIds || (it.id in removed && !(current.isInterrupted && current.locked(it)))
        }.map { edits[it.id] ?: it }.toMutableList()
        val oldOrder = d.baselineEntries.filter { it.id in edits && !current.locked(byOldId[it.id] ?: it) }.map { it.id }
        val userOrder = desired.filter { it.id in oldBaseline && !current.locked(it) }.map { it.id }
        if (oldOrder != userOrder) {
            val ordered = desired.filter { it.id in oldBaseline && !current.locked(it) }.iterator()
            entries = entries.map { if (it.id in userOrder) ordered.next() else it }.toMutableList()
        }
        // User additions retain their position relative to surviving user entries.
        desired.forEachIndexed { index, entry ->
            if (entry.id !in oldBaseline) {
                val previous = desired.take(index).lastOrNull { before -> entries.any { it.id == before.id } }
                val next = desired.drop(index + 1).firstOrNull { after -> entries.any { it.id == after.id } }
                val at = when {
                    previous != null -> entries.indexOfFirst { it.id == previous.id } + 1
                    next != null -> entries.indexOfFirst { it.id == next.id }
                    else -> entries.size
                }
                entries.add(at, entry)
            }
        }
        val rebased = current.copy(entries = baseline, baselineEntries = baseline, nextEntryId = nextId)
        val result = changed(rebased, entries, splits)
        return RebaseResult(result.copy(changeCount = if (result.changeCount == 0) 0 else d.changeCount.coerceAtLeast(1)), dropped)
    }

    private fun changed(d: MixDraft, entries: List<DraftEntry>, splits: List<NewMixDraft> = d.splitOff): MixDraft {
        if (entries == d.entries && splits == d.splitOff) return d
        val identical = splits.isEmpty() && entries.map { it.content() } == d.baselineEntries.map { it.content() }
        return d.copy(entries = entries, splitOff = splits, changeCount = if (identical && d.newFile == null) 0 else d.changeCount + 1)
    }

    private fun mergeParameters(original: Map<String, String>?, edited: Map<String, String>?, fresh: LinkedHashMap<String, String>?): LinkedHashMap<String, String>? {
        if (original == edited) return fresh
        if (edited == null) return null
        val result = LinkedHashMap(fresh.orEmpty())
        original.orEmpty().keys.filterNot { it in edited }.forEach { result.remove(it) }
        edited.forEach { (key, value) -> if (original?.get(key) != value) result[key] = value }
        return result
    }

    private fun matchOccurrences(old: List<DraftEntry>, fresh: List<DraftEntry>): Map<DraftEntry, DraftEntry> {
        val remaining = fresh.toMutableList()
        val matches = linkedMapOf<DraftEntry, DraftEntry>()
        // Match exact source documents first, so a surviving repeated entry keeps its identity.
        old.forEach { entry ->
            val index = remaining.indexOfFirst { it.source == entry.source }
            if (index >= 0) matches[entry] = remaining.removeAt(index)
        }
        old.filterNot { it in matches }.forEach { entry ->
            val index = remaining.indexOfFirst {
                it.source?.path.equals(entry.source?.path, true) && it.pgm().equals(entry.pgm(), true)
            }
            if (index >= 0) matches[entry] = remaining.removeAt(index)
        }
        return matches
    }
}

sealed interface MixWrite {
    data class Write(val file: String, val expectedRevision: String?, val entries: List<EntrySpec>, val completion: Completion?) : MixWrite
    data class Frz(val file: String, val expectedRevision: String, val completion: Completion, val pending: List<EntrySpec>) : MixWrite
    data class Split(val file: String, val expectedRevision: String, val entries: List<EntrySpec>, val newMixes: List<NewMix>, val completion: Completion?) : MixWrite
}

fun buildWrite(d: MixDraft, group: MixGroup?, completedPgms: List<String>, resolutions: Map<String, String>): MixWrite {
    if (d.newFile != null) return MixWrite.Write(d.newFile, null, d.entries.map { it.fullSpec() }, null)
    requireNotNull(group) { "An existing draft requires its mix group" }
    require(group.key == d.groupKey) { "Draft belongs to a different mix group" }
    val locks = completedPgms.map { it.key() }.toSet()
    val choices = resolutions.mapKeys { it.key.key() }
    fun locked(entry: DraftEntry): Boolean = if (choices[entry.pgm().key()] == "frz") entry.source?.remaining == 0 else entry.pgm().key() in locks
    val pending = d.entries.filterNot(::locked).map { entry ->
        if (entry.source == null) EntrySpec.NewPgm(requireNotNull(entry.newPgm)) else EntrySpec.Pending(
            entry.source.pgm,
            entry.quantity.takeIf { it != entry.source.quantity },
            entry.remaining.takeIf { it != entry.source.remaining },
        )
    }
    if (d.splitOff.isNotEmpty()) {
        require(d.baseMixRevision != null) { "FRZ-only split is unsupported by the mix-files service" }
        require(d.splitOff.flatMap { it.entries }.none(::locked)) { "Completed entries cannot be split off" }
        return MixWrite.Split(requireNotNull(group.mix).file, d.baseMixRevision,
            d.entries.filterNot { d.isInterrupted && locked(it) }.map { it.fullSpec(includeCounts = d.isInterrupted) },
            d.splitOff.map { NewMix(it.file, it.entries.map { entry -> entry.fullSpec() }) },
            if (d.isInterrupted) Completion(completedPgms.toList(), resolutions.toMap(), d.baseFrzRevision) else null)
    }
    if (d.isInterrupted) return MixWrite.Frz(requireNotNull(group.frz).file, requireNotNull(d.baseFrzRevision),
        Completion(completedPgms.toList(), resolutions.toMap(), d.baseMixRevision), pending)
    return MixWrite.Write(requireNotNull(group.mix).file, d.baseMixRevision, d.entries.map { it.fullSpec() }, null)
}

private fun String.key(): String = lowercase(Locale.ROOT)
private fun DraftEntry.pgm(): String = source?.pgm ?: requireNotNull(newPgm)
private fun MixDraft.locked(entry: DraftEntry): Boolean = entry.pgm().key() in completedPgms
private fun MixFileEntry.toDraft(id: Long): DraftEntry = DraftEntry(id, this, null, quantity, remaining, LinkedHashMap(parameters))
private fun DraftEntry.fullSpec(includeCounts: Boolean = false): EntrySpec = source?.let {
    val values = LinkedHashMap(parameters ?: it.parameters)
    if (!includeCounts && quantity != it.quantity) {
        values[values.keys.firstOrNull { key -> key.equals("R", true) } ?: "R"] = quantity.toString()
    }
    EntrySpec.Existing(it.path, values, it.fields,
        quantity.takeIf { count -> includeCounts && count != it.quantity },
        remaining.takeIf { count -> includeCounts && count != it.remaining })
} ?: EntrySpec.NewPgm(requireNotNull(newPgm))

// IDs identify occurrences for editing; they are not part of the saved document.
private fun DraftEntry.content(): List<Any?> = listOf(source?.path, source?.pgm, newPgm, quantity, remaining, parameters, source?.fields)
