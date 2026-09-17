package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.ui.theme.BuiltInKKCThemeTokens
import com.kkc.sheettracker.ui.theme.KKCThemeDefinition
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePickerLogicTest {

    private fun theme(id: String, name: String, category: String) =
        KKCThemeDefinition(id = id, name = name, version = 1, category = category, tokens = BuiltInKKCThemeTokens)

    private val themes = listOf(
        theme("kkc-default", "KKC Default", "custom"),
        theme("kkc-forest-shop", "KKC Forest Shop", "custom"),
        theme("nfl-chiefs", "Kansas City Chiefs", "nfl"),
        theme("nfl-eagles", "Philadelphia Eagles", "nfl")
    )

    @Test
    fun customThemesExcludesNflThemes() {
        assertEquals(listOf("KKC Default", "KKC Forest Shop"), customThemes(themes).map { it.name })
    }

    @Test
    fun footballTeamThemesReturnsOnlyNflThemes() {
        assertEquals(listOf("Kansas City Chiefs", "Philadelphia Eagles"), footballTeamThemes(themes).map { it.name })
    }

    @Test
    fun filterThemesByQueryIsCaseInsensitiveSubstringMatch() {
        val nfl = footballTeamThemes(themes)
        assertEquals(listOf("Kansas City Chiefs"), filterThemesByQuery(nfl, "chief").map { it.name })
        assertEquals(listOf("Kansas City Chiefs"), filterThemesByQuery(nfl, "CHIEF").map { it.name })
    }

    @Test
    fun filterThemesByQueryReturnsAllWhenQueryBlank() {
        val nfl = footballTeamThemes(themes)
        assertEquals(nfl, filterThemesByQuery(nfl, ""))
    }

    @Test
    fun filterThemesByQueryReturnsEmptyWhenNoMatch() {
        val nfl = footballTeamThemes(themes)
        assertEquals(emptyList<KKCThemeDefinition>(), filterThemesByQuery(nfl, "zzz"))
    }
}
