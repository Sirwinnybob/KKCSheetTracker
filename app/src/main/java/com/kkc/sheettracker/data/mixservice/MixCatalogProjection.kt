package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.Material

/** A production-facing material row with an optional active mix page scope. */
data class ActiveMixRow(
    val material: Material,
    val activeMix: MixCatalogEntry?,
    val title: String
)

fun activeMixRows(material: Material, catalog: MixCatalogSnapshot?): List<ActiveMixRow> {
    val activeMixes = catalog?.entries.orEmpty().filter { it.lifecycle == MixLifecycle.ACTIVE }
    return when (activeMixes.size) {
        0 -> listOf(ActiveMixRow(material = material, activeMix = null, title = material.materialName))
        1 -> listOf(ActiveMixRow(material = material, activeMix = activeMixes.single(), title = material.materialName))
        else -> activeMixes.map { mix ->
            ActiveMixRow(material = material, activeMix = mix, title = "${mix.name} - ${material.materialName}")
        }
    }
}

fun resolveSelectedActiveMix(catalog: MixCatalogSnapshot?, name: String?): MixCatalogEntry? =
    catalog?.entries?.firstOrNull { it.lifecycle == MixLifecycle.ACTIVE && it.name == name }
