package com.kkc.sheettracker.ui.components

/** Widths reserved for the original leading and trailing action groups in wrap mode. */
internal data class ActionGroupWidthAllocation(
    val leadingWidth: Int,
    val trailingWidth: Int,
    val stackVertically: Boolean
)

/**
 * Allocates one-line space first, then shares overflow between both groups. A group that needs
 * less than its fair share gives the unused width to the other group. If both groups' minimum
 * button widths cannot fit, each gets a full-width line of its own.
 */
internal fun allocateActionGroupWidths(
    leadingNaturalWidth: Int,
    leadingMinWidth: Int,
    trailingNaturalWidth: Int,
    trailingMinWidth: Int,
    availableWidth: Int,
    groupGap: Int
): ActionGroupWidthAllocation {
    val available = availableWidth.coerceAtLeast(0)
    val leadingNatural = leadingNaturalWidth.coerceAtLeast(0)
    val trailingNatural = trailingNaturalWidth.coerceAtLeast(0)
    val leadingMin = leadingMinWidth.coerceIn(0, leadingNatural)
    val trailingMin = trailingMinWidth.coerceIn(0, trailingNatural)
    val hasLeading = leadingNatural > 0 || leadingMin > 0
    val hasTrailing = trailingNatural > 0 || trailingMin > 0

    if (!hasLeading) {
        return ActionGroupWidthAllocation(
            leadingWidth = 0,
            trailingWidth = trailingNatural.coerceAtMost(available),
            stackVertically = false
        )
    }
    if (!hasTrailing) {
        return ActionGroupWidthAllocation(
            leadingWidth = leadingNatural.coerceAtMost(available),
            trailingWidth = 0,
            stackVertically = false
        )
    }

    val gap = groupGap.coerceAtLeast(0)
    if (leadingNatural.toLong() + trailingNatural + gap <= available) {
        return ActionGroupWidthAllocation(leadingNatural, trailingNatural, stackVertically = false)
    }

    val contentWidth = (available - gap).coerceAtLeast(0)
    if (leadingMin.toLong() + trailingMin > contentWidth) {
        return ActionGroupWidthAllocation(available, available, stackVertically = true)
    }

    // The feasible interval guarantees each group its minimum and caps it at its natural width.
    // Clamping the half-share within that interval is the fairest allocation that uses all space.
    val minLeadingShare = maxOf(leadingMin, contentWidth - trailingNatural)
    val maxLeadingShare = minOf(leadingNatural, contentWidth - trailingMin)
    val leadingShare = (contentWidth / 2).coerceIn(minLeadingShare, maxLeadingShare)
    return ActionGroupWidthAllocation(
        leadingWidth = leadingShare,
        trailingWidth = contentWidth - leadingShare,
        stackVertically = false
    )
}
