package com.kkc.sheettracker.ui.mixes

import com.kkc.sheettracker.data.cnc.CncMachine
import com.kkc.sheettracker.data.cnc.CncMachines
import com.kkc.sheettracker.data.cnc.MachineStatus
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.SheetStatus
import java.util.Locale

data class MaterialNavItem(val material: String, val machineId: String, val chip: MaterialChip, val selected: Boolean)
sealed interface MaterialChip {
    data class Left(val n: Int, val interrupted: Boolean) : MaterialChip
    data object Done : MaterialChip
    data object NoMix : MaterialChip
    data object NoPgms : MaterialChip
    data object Offline : MaterialChip
}

data class MixTab(val key: String, val title: String, val interrupted: Boolean, val error: Boolean, val companionMismatch: Boolean)
data class RowModel(
    val key: Long,
    val item: MixItem,
    val label: String,
    val locked: Boolean,
    val tabletStatus: SheetStatus?,
    val frzRemaining: Int?,
    val disagreement: Boolean,
    val chips: List<RowChip>,
    val draggable: Boolean,
    /** All IDs in a collapsed A/Z row must move together, preserving their order. */
    val draftEntryIds: List<Long>,
    /** Indices in the selected catalog file, aligned with entries; additions/restored sources have null. */
    val sourceEntryIndices: List<Int?>,
    /** Current draft values, including opaque parameter spellings. item.entryIndices indexes this draft's full list. */
    val entries: List<DraftEntry>,
    val canRemove: Boolean,
    val canEditCounts: Boolean,
    val canEditParameters: Boolean,
    val canSplit: Boolean,
    val editBlockedReason: String?,
)
enum class RowChip { UNMATCHED, PGM_MISSING, NEEDS_REFRESH, HIDDEN_PAGE, IN_OTHER_MIX }
data class SheetGroups(
    val remaining: List<RowModel>, val unmatched: List<RowModel>, val notInMix: List<SheetPage>, val completed: List<RowModel>,
    val notInMixOtherMixes: Map<Int, List<String>> = emptyMap(),
)
data class MixCardModel(
    val progress: Float, val summary: String, val applyEnabled: Boolean, val applyLabel: String, val applyBlockedReason: String?,
    val applyVisible: Boolean, val splitEnabled: Boolean,
)
sealed interface Banner {
    data object ChangedOnCnc : Banner
    data object SaveUnconfirmed : Banner
    data object ServiceOutdated : Banner
    data object Offline : Banner
    data class Message(val text: String) : Banner
}
data class MixesUiModel(
    val nav: List<MaterialNavItem>, val tabs: List<MixTab>, val selectedTab: String?, val card: MixCardModel?, val groups: SheetGroups?,
    val secondPassApply: String?, val banner: Banner?, val readOnly: Boolean,
    /** One-shot request: the caller refreshes, then clears/acknowledges applyResult. Never retry the write. */
    val refreshNow: Boolean,
    val secondPassApplyEnabled: Boolean,
    val machineStatus: MachineStatus,
)

data class MaterialMixesInput(
    val machine: CncMachine,
    val material: Material,
    val catalogState: CatalogState,
    val pages: List<SheetPage> = sheetPages(material.metadata?.pages.orEmpty()),
    val statusByPage: Map<Int, SheetStatus> = emptyMap(),
    /** May be supplied from CNC PGM inventory when metadata has no matching pages. */
    val hasPgms: Boolean = pages.isNotEmpty(),
) {
    fun statusOf(page: Int): SheetStatus = statusByPage[page] ?: SheetStatus.NOT_STARTED
}

data class MixesUiInput(
    val materials: List<MaterialMixesInput>,
    val selectedMaterial: String?,
    val selectedMachineId: String = CncMachines.scm.id,
    val selectedTab: String? = null,
    val draft: MixDraft? = null,
    val secondPassDraft: SecondPassDraft? = null,
    /** The folder revision captured when editing starts; any subsequent inequality conflicts. */
    val draftFolderRevision: String? = null,
    val conflict: Boolean = false,
    val applyResult: MixFilesResult<MixFilesCatalog>? = null,
    /** Captured before clearing a successfully saved draft, including when the selected tab changes. */
    val savedInterrupted: Boolean = false,
    val applying: Boolean = false,
    val secondPassApplying: Boolean = false,
    val machineStatus: MachineStatus = MachineStatus.Unavailable,
)

fun buildMixesUi(input: MixesUiInput): MixesUiModel {
    val selected = input.materials.firstOrNull {
        it.machine.id == input.selectedMachineId && it.material.materialName == input.selectedMaterial
    }
    val catalog = selected?.catalogState?.catalog()
    val active = catalog?.let(::groupMixFiles).orEmpty().filter { it.mix != null || it.frz != null }
    // A CNC-side delete must not make the user's open draft disappear with its former active tab.
    val draftTab = input.draft?.takeIf { d -> active.none { it.key == d.groupKey } }
    val tabs = active.map { MixTab(it.key, it.title, it.isInterrupted, it.errorFile != null, it.primary.companionMismatch) } +
        listOfNotNull(draftTab?.let { MixTab(it.groupKey, (it.newFile ?: it.groupKey).substringBeforeLast('.'), it.isInterrupted, false, false) })
    val tab = input.selectedTab?.takeIf { key -> tabs.any { it.key == key } } ?: draftTab?.groupKey ?: tabs.firstOrNull()?.key
    val group = active.firstOrNull { it.key == tab }
    val draft = input.draft?.takeIf { it.groupKey == tab }
    val error = (input.applyResult as? MixFilesResult.Rejected)?.error
    val outdated = selected?.catalogState is CatalogState.Outdated || input.applyResult is MixFilesResult.ServiceOutdated
    val offline = selected?.catalogState is CatalogState.Offline
    val unavailable = selected == null || selected.catalogState is CatalogState.Loading
    val readOnly = outdated || offline || unavailable || group?.errorFile != null
    val changed = input.conflict || error?.code == "revision_conflict" || (draft != null && (
        (input.draftFolderRevision != null && catalog?.folderRevision != input.draftFolderRevision) ||
            (draft.newFile == null && (draft.baseMixRevision != group?.mix?.revision || draft.baseFrzRevision != group?.frz?.revision))
        ))
    val unconfirmed = input.applyResult is MixFilesResult.Offline
    val banner = when {
        unconfirmed -> Banner.SaveUnconfirmed
        outdated -> Banner.ServiceOutdated
        changed -> Banner.ChangedOnCnc
        offline -> Banner.Offline
        error != null -> Banner.Message(errorCopy(error))
        input.applyResult is MixFilesResult.Ok && input.savedInterrupted -> Banner.Message("Reload this mix in PanelMac")
        group?.errorFile != null -> Banner.Message(group.errorFile!!.error!!)
        else -> null
    }
    val rows = if (selected != null && (group != null || draft != null)) {
        buildSheetGroups(selected, group, active, draft, readOnly || input.applying)
    } else null
    val card = rows?.let {
        val total = it.remaining.size + it.unmatched.size + it.completed.size
        val count = draft?.changeCount ?: 0
        val block = when {
            outdated -> "The CNC mix service needs updating"
            offline || unavailable -> "Connect to the CNC before saving"
            group?.errorFile != null -> "Restore a revision or delete this unreadable mix"
            changed -> "This mix changed on the CNC"
            unconfirmed -> "Refresh to confirm whether the CNC saved this mix"
            input.applying -> "Saving changes"
            (group?.isInterrupted == true || draft?.isInterrupted == true) && it.unmatched.isNotEmpty() -> "${it.unmatched.size} sheets in this mix don't match the job — fix them before saving"
            else -> null
        }
        MixCardModel(
            if (total == 0) 0f else it.completed.size.toFloat() / total,
            "${it.completed.size} of $total cut · ${total - it.completed.size} remaining",
            draft != null && count > 0 && block == null,
            "Apply $count ${if (count == 1) "change" else "changes"}", block,
            draft != null, it.remaining.any { row -> row.canSplit } || it.unmatched.any { row -> row.canSplit },
        )
    }
    val secondCount = input.secondPassDraft?.changeCount ?: 0
    // A mix revision conflict or unreadable mix does not affect the separate PGM edit draft.
    val secondEnabled = secondCount > 0 && !outdated && !offline && !unavailable && !input.secondPassApplying
    return MixesUiModel(input.materials.map { material ->
        MaterialNavItem(material.material.materialName, material.machine.id, navChip(material), material == selected)
    }, tabs, tab, card, rows, "Apply 2nd pass ($secondCount)".takeIf { secondCount > 0 }, banner, readOnly,
        unconfirmed, secondEnabled, input.machineStatus)
}

private fun CatalogState.catalog(): MixFilesCatalog? = when (this) {
    is CatalogState.Live -> catalog
    is CatalogState.Offline -> cached
    is CatalogState.Outdated -> cached
    else -> null
}

private fun navChip(input: MaterialMixesInput): MaterialChip {
    if (input.catalogState is CatalogState.Offline || input.catalogState is CatalogState.Loading) return MaterialChip.Offline
    if (!input.hasPgms) return MaterialChip.NoPgms
    val groups = input.catalogState.catalog()?.let(::groupMixFiles).orEmpty().filter { it.mix != null || it.frz != null }
    if (groups.isEmpty()) return MaterialChip.NoMix
    val left = input.pages.count { !isLockedStatus(input.statusOf(it.pageNumber)) }
    return if (left == 0 && input.pages.isNotEmpty()) MaterialChip.Done else MaterialChip.Left(left, groups.any { it.isInterrupted })
}

private fun buildSheetGroups(
    material: MaterialMixesInput, group: MixGroup?, active: List<MixGroup>, draft: MixDraft?, readOnly: Boolean,
): SheetGroups {
    val source = group?.primary?.entries.orEmpty()
    val baseline = draft?.baselineEntries ?: source.mapIndexed { i, e -> e.asDraft(i + 1L) }
    val entries = draft?.entries ?: baseline
    // Match baseline occurrences once, before any reorder, so repeated PGMs never steal each other's source indices.
    val available = source.indices.toMutableList()
    val sourceIndices = baseline.associate { entry ->
        val index = available.firstOrNull { source[it] == entry.source }
        if (index != null) available.remove(index)
        entry.id to index
    }
    val effective = entries.map { entry ->
        entry.source?.copy(quantity = entry.quantity, remaining = entry.remaining,
            parameters = LinkedHashMap(entry.parameters.orEmpty())) ?: MixFileEntry(
            "", requireNotNull(entry.newPgm), linkedMapOf(), null, entry.quantity, entry.remaining, true, false)
    }
    val otherMembership = mutableMapOf<String, MutableList<String>>()
    active.filter { it.key != group?.key && it.key != draft?.groupKey }.forEach { other ->
        other.primary.entries.forEach { otherMembership.getOrPut(it.pgm.key()) { mutableListOf() }.add(other.title) }
    }
    val interrupted = group?.isInterrupted == true || draft?.isInterrupted == true
    val splitSupported = group?.mix != null && draft?.newFile == null
    val rows = matchEntries(effective, material.pages).map { item ->
        val values = item.entryIndices.map { entries[it] }
        val fileEntries = item.entryIndices.map { effective[it] }
        val sheet = item as? MixItem.Sheet
        val status = sheet?.let { material.statusOf(it.page.pageNumber) }
        val locked = status?.let(::isLockedStatus) == true
        val editable = !readOnly && !locked
        val new = values.any { it.source == null }
        val label = sheet?.let {
            "Sheet ${it.page.pageNumber}${if (it.aOnly == true) " A" else if (it.zOnly == true) " Z" else ""} · qty ${it.quantity}"
        } ?: (item as MixItem.Unmatched).pgm
        RowModel(values.first().id, item, label, locked, status, sheet?.remaining.takeIf { interrupted },
            interrupted && sheet != null && sheet.remaining != null && (locked != (sheet.remaining == 0)),
            buildList {
                if (sheet == null) add(RowChip.UNMATCHED)
                if (fileEntries.any { !it.pgmExists }) add(RowChip.PGM_MISSING)
                if (fileEntries.any { it.headerDrift }) add(RowChip.NEEDS_REFRESH)
                if (sheet?.page?.hidden == true) add(RowChip.HIDDEN_PAGE)
                if (fileEntries.any { it.pgm.key() in otherMembership }) add(RowChip.IN_OTHER_MIX)
            }, editable, values.map { it.id }, values.map { sourceIndices[it.id] }, values,
            !readOnly && (!locked || !interrupted), editable && !new, editable && !new && !interrupted,
            editable && splitSupported,
            when {
                readOnly -> "This mix is read-only"
                locked -> "Completed sheets are locked"
                new -> "Apply the new sheet before editing its quantity or parameters"
                else -> null
            })
    }
    val matchedPages = rows.mapNotNull { (it.item as? MixItem.Sheet)?.page?.pageNumber }.toSet()
    val notInMix = material.pages.filterNot { it.pageNumber in matchedPages }
    return SheetGroups(rows.filter { !it.locked && it.item is MixItem.Sheet }, rows.filter { it.item is MixItem.Unmatched },
        notInMix, rows.filter { it.locked }, notInMix.mapNotNull { page ->
            val names = page.pgmFiles.flatMap { otherMembership[it.key()].orEmpty() }.distinct()
            (page.pageNumber to names).takeIf { names.isNotEmpty() }
        }.toMap())
}

private fun MixFileEntry.asDraft(id: Long) = DraftEntry(id, this, null, quantity, remaining, LinkedHashMap(parameters))
private fun String.key() = lowercase(Locale.ROOT)

fun errorCopy(error: MixFilesError): String = when (error.code) {
    "revision_conflict" -> "This mix changed on the CNC"
    "name_exists" -> "A mix with this name already exists. Choose another name."
    "not_found" -> "This mix or material could not be found. Refresh and try again."
    "missing_program" -> error.pgm?.let { "PGM file missing: $it" } ?: "A PGM file is missing. Refresh and try again."
    "mix_unreadable" -> "Can't read this mix. Restore a revision or delete it."
    "invalid_entries" -> "The CNC couldn't accept these mix entries. Check the sheets and counts."
    "companion_requires_completion" -> "Refresh this interrupted mix before saving its completion state."
    "frz_completion_mismatch" -> "The tablet and FRZ disagree about completed sheets. Choose which to use for each sheet."
    "frz_edit_disabled" -> when (error.edit) {
        "add" -> "Adding sheets to interrupted mixes is switched off on the CNC"
        "remove" -> "Removing sheets from interrupted mixes is switched off on the CNC"
        "count" -> "Count changes on interrupted mixes are switched off on the CNC"
        else -> "Adding / removing / count changes on interrupted mixes are switched off on the CNC"
    }
    "machine_busy_with_mix" -> "The CNC is cutting this mix. Pause it first."
    "state_unreadable" -> "Can't read the CNC's state right now. Try again in a moment."
    "companion_write_failed" -> if (error.frzRestored == false) {
        "The companion mix couldn't be saved and the FRZ couldn't be restored. Refresh and check both files before trying again."
    } else "The companion mix couldn't be saved. Refresh and check both files before trying again."
    "write_failed" -> "The CNC couldn't save the mix. The previous file is intact. Try again."
    "bad_request" -> "The CNC couldn't understand this request. Refresh and try again."
    "request_too_large" -> "This mix is too large to save in one request."
    "internal" -> "The CNC mix service encountered an error. Try again in a moment."
    "server_busy" -> "The CNC mix service is busy. Try again in a moment."
    else -> error.message.ifBlank { "The CNC couldn't complete this request. Refresh and try again." }
}

enum class GateDecision { APPLY, DEFER, CONFLICT }

/** One gate per material. Drafts and an active drag keep their applied snapshot until explicitly resolved. */
class CatalogGate {
    var appliedCatalog: MixFilesCatalog? = null
        private set
    var deferredCatalog: MixFilesCatalog? = null
        private set

    fun onCatalog(c: MixFilesCatalog, dragging: Boolean, hasDraft: Boolean): GateDecision {
        val old = appliedCatalog
        require(old == null || (old.job == c.job && old.material == c.material)) { "Use one CatalogGate per material" }
        if (dragging) {
            deferredCatalog = c
            return GateDecision.DEFER
        }
        if (hasDraft && old != null && old.folderRevision != c.folderRevision) {
            deferredCatalog = c
            return GateDecision.CONFLICT
        }
        accept(c)
        return GateDecision.APPLY
    }

    /** Call after staging the move, so hasDraft reflects the drop's newly created draft. */
    fun onDrop(hasDraft: Boolean): GateDecision = deferredCatalog?.let { onCatalog(it, false, hasDraft) } ?: GateDecision.APPLY

    /** Explicit Load theirs / successful rebase / successful save establishes the next baseline. */
    fun accept(c: MixFilesCatalog) {
        appliedCatalog = c
        deferredCatalog = null
    }
}
