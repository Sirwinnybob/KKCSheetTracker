package com.kkc.sheettracker.ui.theme

import androidx.compose.ui.unit.dp

object KKCSpacing {
    // Base scale
    val xxs  = 4.dp
    val xs   = 6.dp
    val s    = 8.dp
    val m    = 10.dp
    val l    = 12.dp
    val ml   = 14.dp
    val xl   = 16.dp
    val xxl  = 20.dp
    val xl3  = 24.dp
    val xl4  = 32.dp

    // Typography-density gaps
    val textLineGap               = 3.dp   // stacked label sub-line gap
    val chipVertical              = 5.dp   // chip/badge vertical padding

    // Component-specific tokens
    val progressBarHeightThin     = 3.dp   // sub-header progress bar
    val progressBarHeightStandard = 4.dp   // primary section progress bar

    // Semantic aliases
    val screenHorizontal     = xl     // 16.dp
    val cardPadding          = xl     // 16.dp — primary card interior
    val cardPaddingCompact   = ml     // 14.dp — alert/assembly cards
    val cardPaddingSmall     = l      // 12.dp — compact cards, modals
    val listContentHorizontal = xl   // 16.dp
    val listContentVertical  = l     // 12.dp
    val listItemSpacing      = l     // 12.dp
    val sectionHeaderH       = l     // 12.dp
    val sectionHeaderV       = m     // 10.dp — sub-header vertical
    val sectionHeaderVPrimary = s    // 8.dp  — primary header vertical
    val sheetHorizontal      = xxl   // 20.dp
    val sheetBottomSafe      = xl4   // 32.dp
    val sheetItemSpacing     = ml    // 14.dp
    val inCardSpacing        = s     // 8.dp
    val tightSpacing         = xs    // 6.dp
    val chipHorizontal       = m     // 10.dp
    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // KEEP IN SYNC — HOURS TRACKER MIRRORS THIS NAVBAR
    // When KKC opens Hours Tracker (com.example.timecard) it sends this bar's resolved look as
    // intent extras and Hours Tracker draws a copy of the FULL (labels shown) state of this bar.
    // Mirror lives in C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\kkcnav\
    //   KkcNavBar.kt          ← MorphingNavBar + MorphingNavIconRow (full state)
    //   KkcNavBarModel.kt     ← labels, Calc-before-Hours slot order, badge rules, tint rules, alpha clamp
    //   KkcNavIcons.kt        ← NavIcons.kt (verbatim copy, package changed)
    //   KkcIconDsl.kt         ← IconDsl.kt (verbatim copy, package changed)
    //   KkcNavTypography.kt   ← Type.kt InterFontFamily + labelSmall
    //   KkcNavBarContract.kt  ← navigation/KkcNavBarContract.kt
    // Values that MUST match: side margin 24dp, bottom gap 12dp, bar corner 20dp, row min height
    // 44dp + padding 24x4dp, SpaceEvenly + weight(1f) slots, item padding 14x8dp, spacedBy 3dp,
    // icon 22dp, Calc slot before Hours, tints (bold: frosted content / @0.8; else primary /
    // onSurfaceVariant), selection bg (bold: gradient @0.55; else surfaceVariant; shape
    // shapes.medium), badges (Supply = supply count, Library = safety count), Surface color
    // (transparent under Haze, else frosted base @ alpha.coerceIn(0.5,0.95)), shadow 3dp (0 when
    // shadows disabled or WebView blur suppressed), Haze style (blur coerceAtLeast 1dp), label
    // style Inter Medium 11sp/16sp/0.5sp (Bold when selected), label maxLines 1 + ellipsis only
    // when animations are on (none in low-end and for Calc), animation specs (NavSpringDp,
    // snap() when low-end animations are off).
    // Change any of these here → change the mirror in the SAME session. A new value the mirror
    // needs goes through KkcNavBarContract and bumps VERSION in BOTH repos.
    // Full rules: KKCSheetTracker CLAUDE.md "KKC navbar mirror (Hours Tracker)".
    // ═══════════════════════════════════════════════════════════════════════════════════════════
    val navBarHorizontal     = xl3   // 24.dp — minimized nav bar

    val floatingNavSideMargin    = xl    // 16.dp — full nav bar floating side margin
    val floatingNavMinSideMargin = xl3   // 24.dp — minimized nav floating side margin
    val floatingNavBottomGap     = l     // 12.dp — gap above gesture bar
}
