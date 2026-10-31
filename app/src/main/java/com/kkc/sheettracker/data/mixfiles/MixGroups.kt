package com.kkc.sheettracker.data.mixfiles

import java.util.Locale

data class MixGroup(
    val key: String,
    val title: String,
    val mix: MixFileRow?,
    val frz: MixFileRow?,
    val mixRevisions: List<MixFileRow>,
    val frzRevisions: List<MixFileRow>,
) {
    val isInterrupted: Boolean get() = frz != null

    // Orphan revisions remain displayable/restorable; callers must check mix/frz for edits.
    val primary: MixFileRow
        get() = frz ?: mix ?: (mixRevisions + frzRevisions).sortedWith(revisionOrder).first()

    val errorFile: MixFileRow?
        get() = listOfNotNull(frz, mix).firstOrNull { it.error != null }
            ?: if (mix == null && frz == null) primary.takeIf { it.error != null } else null
}

private val revisionOrder = compareByDescending<MixFileRow> { it.revisionNumber != null }
    .thenByDescending { it.revisionNumber }
    .thenByDescending { it.file.lowercase(Locale.ROOT) }

fun groupMixFiles(catalog: MixFilesCatalog): List<MixGroup> {
    val active = catalog.files.filter { it.kind != MixFileKind.REVISION }
    val byFile = active.associateBy { it.file.lowercase(Locale.ROOT) }
    val groups = mutableListOf<MixGroup>()
    val groupByFile = mutableMapOf<String, Int>()

    for (file in active) {
        val fileKey = file.file.lowercase(Locale.ROOT)
        if (fileKey in groupByFile) continue
        val companion = file.companion?.lowercase(Locale.ROOT)?.let(byFile::get)?.takeIf {
            it.kind != file.kind && it.companion.equals(file.file, ignoreCase = true)
        }
        val members = listOfNotNull(file, companion)
        val mix = members.firstOrNull { it.kind == MixFileKind.MIX }
        val frz = members.firstOrNull { it.kind == MixFileKind.FRZ }
        val name = (mix ?: frz!!).file
        val index = groups.size
        groups += MixGroup(name.lowercase(Locale.ROOT), name.substringBeforeLast('.'), mix, frz, emptyList(), emptyList())
        members.forEach { groupByFile[it.file.lowercase(Locale.ROOT)] = index }
    }

    for (revision in catalog.files.filter { it.kind == MixFileKind.REVISION }) {
        val original = revision.revisionOf ?: revision.file
        val originalKey = original.lowercase(Locale.ROOT)
        val index = groupByFile.getOrPut(originalKey) {
            groups += MixGroup(originalKey, original.substringBeforeLast('.'), null, null, emptyList(), emptyList())
            groups.lastIndex
        }
        val group = groups[index]
        groups[index] = if (original.endsWith(".frz", ignoreCase = true)) {
            group.copy(frzRevisions = group.frzRevisions + revision)
        } else {
            group.copy(mixRevisions = group.mixRevisions + revision)
        }
    }

    return groups.map {
        it.copy(mixRevisions = it.mixRevisions.sortedWith(revisionOrder), frzRevisions = it.frzRevisions.sortedWith(revisionOrder))
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}
