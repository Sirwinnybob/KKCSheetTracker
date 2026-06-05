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
    val navBarHorizontal     = xl3   // 24.dp — minimized nav bar
    val floatingNavSideMargin    = xl    // 16.dp — full nav bar floating side margin
    val floatingNavMinSideMargin = xl3   // 24.dp — minimized nav floating side margin
    val floatingNavBottomGap     = l     // 12.dp — gap above gesture bar
}
