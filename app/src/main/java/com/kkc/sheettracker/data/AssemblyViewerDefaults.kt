package com.kkc.sheettracker.data

enum class AssemblyViewLayout { SPLIT, SINGLE }

enum class AssemblyPaneView { ASSEMBLY, PLANS, DELIVERY, THREE_D, CHECKLIST }

data class AssemblyViewerDefaults(
    val layout: AssemblyViewLayout = AssemblyViewLayout.SPLIT,
    val firstPane: AssemblyPaneView = AssemblyPaneView.PLANS,
    val secondPane: AssemblyPaneView = AssemblyPaneView.ASSEMBLY,
    val hideUiOnOpen: Boolean = false,
)
