package com.kkc.sheettracker.data.models

/**
 * Handoff item flags (HT METADATA_CONTRACT.md §2.12). Every flag is optional; null = Auto (HT's
 * packet rules decide). Wire values are lower-case and case-sensitive — never write `name`.
 */
enum class HandoffTruck(val wire: String, val label: String) {
    LOOSE("loose", "Ship loose"),
    SHOP("shop", "Shop install"),
    LATER("later", "Coming later");

    companion object {
        fun fromWire(value: String?): HandoffTruck? = entries.firstOrNull { it.wire == value }
    }
}

enum class HandoffCount(val wire: String, val label: String) {
    QTY("qty", "Count by qty"),
    LINE("line", "Count by line");

    companion object {
        fun fromWire(value: String?): HandoffCount? = entries.firstOrNull { it.wire == value }
    }
}

data class HandoffItemFlags(
    val truck: HandoffTruck? = null,
    val count: HandoffCount? = null
) {
    val isAuto: Boolean get() = truck == null && count == null
}

/** `handoff.json` key parts. The key is `"<source>:<id>"`; ids may themselves contain colons. */
data class HandoffFlagRef(val source: String, val id: String) {
    val key: String get() = "$source:$id"

    companion object {
        const val BOARD_STOCK = "board_stock"
        const val SPECIALTY_ITEMS = "specialty_items"
        const val CHECKLIST = "checklist"
        val SOURCES = setOf(BOARD_STOCK, SPECIALTY_ITEMS, CHECKLIST)

        /** Splits on the FIRST colon only. */
        fun fromKey(key: String): HandoffFlagRef? {
            val split = key.indexOf(':')
            if (split <= 0 || split == key.length - 1) return null
            return HandoffFlagRef(key.substring(0, split), key.substring(split + 1))
        }
    }
}

/** One row of HT's `GET /api/jobs/{folder}/handoff/truck-list`, reduced to what the tablet shows. */
data class HandoffTruckListEntry(
    val ref: HandoffFlagRef,
    val autoTruck: HandoffTruck?,
    val effectiveTruck: HandoffTruck?,
    val effectiveCount: HandoffCount?,
    val excluded: Boolean
)
