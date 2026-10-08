package com.kkc.sheettracker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp

private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = com.kkc.sheettracker.R.array.com_google_android_gms_fonts_certs
)

private val interFont = GoogleFont("Inter")
private val jetBrainsMonoFont = GoogleFont("JetBrains Mono")

val InterFontFamily = FontFamily(
    Font(googleFont = interFont, fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = interFont, fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = interFont, fontProvider = fontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = interFont, fontProvider = fontProvider, weight = FontWeight.Bold)
)

val MonoFontFamily = FontFamily(
    Font(googleFont = jetBrainsMonoFont, fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = jetBrainsMonoFont, fontProvider = fontProvider, weight = FontWeight.SemiBold)
)

val DimensionTextStyle = TextStyle(
    fontFamily = MonoFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.sp
)

val KKCTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 57.sp,
        lineHeight = 64.sp
    ),
    displayMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 45.sp,
        lineHeight = 52.sp
    ),
    displaySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
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
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
