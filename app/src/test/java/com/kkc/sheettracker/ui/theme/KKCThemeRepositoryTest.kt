package com.kkc.sheettracker.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KKCThemeRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun loadCatalogIncludesValidSyncedThemeAndIgnoresUnknownKeys() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(
            baseDir = baseDir,
            filename = "shop-blue.json",
            body = validThemeJson(
                id = "kkc-shop-blue",
                extra = ""","unknownFutureKey":{"kept":"ignored"}"""
            )
        )

        val catalog = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()

        assertTrue(catalog.invalidThemes.isEmpty())
        assertNotNull(catalog.themes.firstOrNull { it.id == KKCThemeRepository.BUILT_IN_THEME_ID })
        val syncedTheme = catalog.themes.firstOrNull { it.id == "kkc-shop-blue" }
        assertNotNull(syncedTheme)
        assertEquals("KKC Shop Blue", syncedTheme?.name)
    }

    @Test
    fun parsedThemeColorsSupportComposeCopyAlpha() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(baseDir, "shop-blue.json", validThemeJson(id = "kkc-shop-blue"))

        val catalog = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()
        val surface = catalog.themes.first { it.id == "kkc-shop-blue" }.tokens.light.surface

        assertNotNull(surface.copy(alpha = 0.5f).colorSpace)
    }

    @Test
    fun themeHeaderSvgPathResolvesRelativeToThemeDirectory() {
        val baseDir = temp.newFolder("Ready Jobs")
        val svgFile = writeHeaderSvg(baseDir, "graphics/header-shop.svg")
        writeTheme(
            baseDir = baseDir,
            filename = "shop-blue.json",
            body = validThemeJson(
                id = "kkc-shop-blue",
                header = """
                  ,"header": {
                    "background": "graphics/header-shop.svg",
                    "alpha": 0.24,
                    "contentScale": "fillWidth"
                  }
                """
            )
        )

        val catalog = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()
        val header = catalog.themes.first { it.id == "kkc-shop-blue" }.tokens.header

        assertEquals(svgFile.absolutePath, header.backgroundPath)
        assertEquals(0.24f, header.alpha)
        assertEquals(KKCThemeHeaderContentScale.FILL_WIDTH, header.contentScale)
    }

    @Test
    fun missingThemeHeaderSvgFallsBackToGradientAndReportsMessage() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(
            baseDir = baseDir,
            filename = "shop-blue.json",
            body = validThemeJson(
                id = "kkc-shop-blue",
                header = """
                  ,"header": {
                    "background": "graphics/missing.svg",
                    "alpha": 0.24
                  }
                """
            )
        )

        val catalog = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()
        val theme = catalog.themes.first { it.id == "kkc-shop-blue" }

        assertEquals(null, theme.tokens.header.backgroundPath)
        assertTrue(catalog.loadMessages.any { it.contains("graphics/missing.svg") })
    }

    @Test
    fun loadCatalogRejectsInvalidHexAndKeepsBuiltInFallback() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(
            baseDir = baseDir,
            filename = "bad.json",
            body = validThemeJson(id = "bad-theme").replace("#1E5FAF", "blue")
        )

        val catalog = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()

        assertEquals(listOf(KKCThemeRepository.BUILT_IN_THEME_ID), catalog.themes.map { it.id })
        assertEquals(1, catalog.invalidThemes.size)
        assertTrue(catalog.invalidThemes.single().message.contains("light.primary"))
        assertEquals(KKCThemeRepository.BUILT_IN_THEME_ID, catalog.activeTheme.id)
    }

    @Test
    fun syncedDefaultSelectsThemeWhenNoLocalOverrideIsEnabled() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(baseDir, "shop-blue.json", validThemeJson(id = "kkc-shop-blue"))
        writeActiveTheme(baseDir, "kkc-shop-blue")

        val catalog = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()

        assertEquals("kkc-shop-blue", catalog.syncedDefaultThemeId)
        assertEquals("kkc-shop-blue", catalog.activeTheme.id)
    }

    @Test
    fun localTabletSelectionBeatsSyncedDefault() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(baseDir, "shop-blue.json", validThemeJson(id = "kkc-shop-blue"))
        writeTheme(baseDir, "shop-green.json", validThemeJson(id = "kkc-shop-green", name = "KKC Shop Green"))
        writeActiveTheme(baseDir, "kkc-shop-blue")
        val prefs = FakeThemePreferences(
            followSyncedDefaultValue = true,
            overrideThemeIdValue = "kkc-shop-green"
        )

        val catalog = KKCThemeRepository(baseDir, prefs).loadCatalog()

        assertEquals("kkc-shop-blue", catalog.syncedDefaultThemeId)
        assertEquals("kkc-shop-green", catalog.activeTheme.id)
    }

    @Test
    fun noLocalTabletSelectionUsesSyncedDefault() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(baseDir, "shop-blue.json", validThemeJson(id = "kkc-shop-blue"))
        writeActiveTheme(baseDir, "kkc-shop-blue")
        val prefs = FakeThemePreferences(
            followSyncedDefaultValue = false,
            overrideThemeIdValue = null
        )

        val catalog = KKCThemeRepository(baseDir, prefs).loadCatalog()

        assertEquals("kkc-shop-blue", catalog.activeTheme.id)
    }

    @Test
    fun localTabletSelectionStillWinsForLegacyFollowSyncedPreference() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(baseDir, "shop-blue.json", validThemeJson(id = "kkc-shop-blue"))
        writeTheme(baseDir, "shop-green.json", validThemeJson(id = "kkc-shop-green", name = "KKC Shop Green"))
        writeActiveTheme(baseDir, "kkc-shop-blue")
        val prefs = FakeThemePreferences(
            followSyncedDefaultValue = true,
            overrideThemeIdValue = "kkc-shop-green"
        )

        val catalog = KKCThemeRepository(baseDir, prefs).loadCatalog()

        assertEquals("kkc-shop-green", catalog.activeTheme.id)
    }

    @Test
    fun builtInThemeCanBePinnedAsLocalOverride() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(baseDir, "shop-blue.json", validThemeJson(id = "kkc-shop-blue"))
        writeActiveTheme(baseDir, "kkc-shop-blue")
        val prefs = FakeThemePreferences(
            followSyncedDefaultValue = false,
            overrideThemeIdValue = KKCThemeRepository.BUILT_IN_THEME_ID
        )

        val catalog = KKCThemeRepository(baseDir, prefs).loadCatalog()

        assertEquals(KKCThemeRepository.BUILT_IN_THEME_ID, catalog.activeTheme.id)
    }

    @Test
    fun setOverrideThemeIdKeepsBuiltInThemeId() {
        val baseDir = temp.newFolder("Ready Jobs")
        val prefs = FakeThemePreferences()

        KKCThemeRepository(baseDir, prefs).setOverrideThemeId(KKCThemeRepository.BUILT_IN_THEME_ID)

        assertEquals(KKCThemeRepository.BUILT_IN_THEME_ID, prefs.overrideThemeId)
    }

    @Test
    fun missingSyncedDefaultFallsBackToBuiltInTheme() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(baseDir, "shop-blue.json", validThemeJson(id = "kkc-shop-blue"))
        writeActiveTheme(baseDir, "does-not-exist")

        val catalog = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()

        assertEquals("does-not-exist", catalog.syncedDefaultThemeId)
        assertEquals(KKCThemeRepository.BUILT_IN_THEME_ID, catalog.activeTheme.id)
        assertFalse(catalog.loadMessages.isEmpty())
    }

    private fun writeTheme(baseDir: File, filename: String, body: String) {
        val themeDir = File(baseDir, ".metadata/themes").apply { mkdirs() }
        File(themeDir, filename).writeText(body)
    }

    private fun writeActiveTheme(baseDir: File, themeId: String) {
        val themeDir = File(baseDir, ".metadata/themes").apply { mkdirs() }
        File(themeDir, "active_theme.json").writeText("""{"themeId":"$themeId"}""")
    }

    private fun writeHeaderSvg(baseDir: File, relativePath: String): File {
        val themeDir = File(baseDir, ".metadata/themes").apply { mkdirs() }
        return File(themeDir, relativePath).apply {
            parentFile?.mkdirs()
            writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 80"><rect width="800" height="80" fill="#1E5FAF"/></svg>""")
        }
    }

    private fun validThemeJson(
        id: String,
        name: String = "KKC Shop Blue",
        extra: String = "",
        header: String = ""
    ): String {
        return """
            {
              "id": "$id",
              "name": "$name",
              "version": 1,
              "light": { "primary": "#1E5FAF", "background": "#EFF4FA", "surface": "#FFFFFF" },
              "dark": { "primary": "#79B2FF", "background": "#040A14", "surface": "#162438" },
              "status": { "complete": "#388E3C", "bad": "#C62828", "skip": "#E65100", "inProgress": "#1565C0" },
              "surface": { "cardAlpha": 1.0, "headerTintAlpha": 0.10 },
              "frosted": { "backgroundAlpha": 0.72, "blurDp": 14 },
              "shape": { "smallDp": 10, "mediumDp": 18, "largeDp": 24 },
              "spacingScale": 1.0
              $header
              $extra
            }
        """.trimIndent()
    }
}

private class FakeThemePreferences(
    private var followSyncedDefaultValue: Boolean = true,
    private var overrideThemeIdValue: String? = null
) : KKCThemePreferenceStore {
    override var followSyncedDefault: Boolean
        get() = followSyncedDefaultValue
        set(value) { followSyncedDefaultValue = value }

    override var overrideThemeId: String?
        get() = overrideThemeIdValue
        set(value) { overrideThemeIdValue = value }
}
