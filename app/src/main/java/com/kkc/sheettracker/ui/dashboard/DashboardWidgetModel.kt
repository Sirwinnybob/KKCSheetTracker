package com.kkc.sheettracker.ui.dashboard

enum class DashboardAccent {
    NEUTRAL,
    INFO,
    SUCCESS,
    WARNING,
    DANGER
}

data class DashboardStatModel(
    val label: String,
    val value: String,
    val supportingText: String? = null,
    val accent: DashboardAccent = DashboardAccent.NEUTRAL,
    val action: DashboardStatAction? = null
)

enum class DashboardStatAction {
    BAD_PARTS,
    SKIPPED
}

sealed interface DashboardItemModel {
    val id: String
    val title: String
    val subtitle: String
    val supportingText: String?
    val accent: DashboardAccent
}

data class DashboardProgressItemModel(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val supportingText: String? = null,
    val progressLabel: String? = null,
    val progressFraction: Float? = null,
    override val accent: DashboardAccent = DashboardAccent.NEUTRAL
): DashboardItemModel

data class DashboardInventoryItemModel(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val supportingText: String? = null,
    val badge: String? = null,
    override val accent: DashboardAccent = DashboardAccent.NEUTRAL
): DashboardItemModel

data class SpecialtySummary(
    val jobCount: Int = 0,
    val completedItems: Int = 0,
    val totalItems: Int = 0
)

sealed interface DashboardWidgetModel {
    val key: String

    data class Hero(
        override val key: String,
        val title: String,
        val primaryValue: String,
        val secondaryValue: String? = null,
        val tertiaryValue: String? = null,
        val progressFraction: Float? = null,
        val accent: DashboardAccent = DashboardAccent.INFO
    ) : DashboardWidgetModel

    data class StatsRow(
        override val key: String,
        val stats: List<DashboardStatModel>
    ) : DashboardWidgetModel

    data class AlertBlock(
        override val key: String,
        val title: String,
        val message: String,
        val supportingText: String? = null,
        val accent: DashboardAccent = DashboardAccent.NEUTRAL
    ) : DashboardWidgetModel

    data class RecentItemsBlock(
        override val key: String,
        val title: String,
        val items: List<DashboardProgressItemModel>,
        val emptyMessage: String = "Nothing is in progress right now."
    ) : DashboardWidgetModel

    data class JobsBlock(
        override val key: String,
        val title: String,
        val items: List<DashboardProgressItemModel>,
        val summary: String? = null,
        val emptyMessage: String = "No jobs are available yet."
    ) : DashboardWidgetModel

    data class InventoryBlock(
        override val key: String,
        val title: String,
        val subtitle: String? = null,
        val items: List<DashboardInventoryItemModel>,
        val summary: String? = null,
        val emptyMessage: String = "No inventory items are available."
    ) : DashboardWidgetModel
}
