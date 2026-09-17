package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.ui.theme.KKCThemeDefinition

internal const val CUSTOM_THEME_CATEGORY = "custom"
internal const val NFL_THEME_CATEGORY = "nfl"

internal fun customThemes(themes: List<KKCThemeDefinition>): List<KKCThemeDefinition> =
    themes.filter { it.category == CUSTOM_THEME_CATEGORY }

internal fun footballTeamThemes(themes: List<KKCThemeDefinition>): List<KKCThemeDefinition> =
    themes.filter { it.category == NFL_THEME_CATEGORY }

internal fun filterThemesByQuery(themes: List<KKCThemeDefinition>, query: String): List<KKCThemeDefinition> =
    if (query.isBlank()) themes else themes.filter { it.name.contains(query, ignoreCase = true) }
