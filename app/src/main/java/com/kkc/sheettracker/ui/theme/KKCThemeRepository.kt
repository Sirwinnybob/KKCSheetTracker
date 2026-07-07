package com.kkc.sheettracker.ui.theme

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

interface KKCThemePreferenceStore {
    var followSyncedDefault: Boolean
    var overrideThemeId: String?
}

class SharedPreferencesKKCThemePreferenceStore(
    private val prefs: SharedPreferences
) : KKCThemePreferenceStore {
    override var followSyncedDefault: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_SYNCED_DEFAULT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FOLLOW_SYNCED_DEFAULT, value).apply()
        }

    override var overrideThemeId: String?
        get() = prefs.getString(KEY_OVERRIDE_THEME_ID, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_OVERRIDE_THEME_ID)
                else putString(KEY_OVERRIDE_THEME_ID, value)
            }.apply()
        }

    companion object {
        const val KEY_FOLLOW_SYNCED_DEFAULT = "theme_follow_synced_default"
        const val KEY_OVERRIDE_THEME_ID = "theme_override_id"
    }
}

data class KKCThemeDefinition(
    val id: String,
    val name: String,
    val version: Int,
    val tokens: KKCThemeTokens
)

data class KKCThemeCatalog(
    val themes: List<KKCThemeDefinition>,
    val activeTheme: KKCThemeDefinition,
    val syncedDefaultThemeId: String?,
    val invalidThemes: List<KKCInvalidTheme>,
    val loadMessages: List<String>,
    val followSyncedDefault: Boolean,
    val overrideThemeId: String?
)

data class KKCInvalidTheme(
    val filename: String,
    val message: String
)

class KKCThemeRepository(
    private val baseDir: File,
    private val preferences: KKCThemePreferenceStore
) {
    private val gson = Gson()

    fun loadCatalog(): KKCThemeCatalog {
        val builtIn = builtInThemeDefinition()
        val themeDir = File(baseDir, THEME_DIR)
        val invalidThemes = mutableListOf<KKCInvalidTheme>()
        val loadMessages = mutableListOf<String>()
        val syncedThemes = themeDir
            .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) && file.name != ACTIVE_THEME_FILE }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { file ->
                parseThemeFile(file, loadMessages).fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        invalidThemes += KKCInvalidTheme(file.name, error.message ?: "Invalid theme")
                        null
                    }
                )
            }
            .orEmpty()

        val themes = listOf(builtIn) + syncedThemes.distinctBy { it.id }
        val syncedDefaultThemeId = readSyncedDefault(themeDir)
        val localThemeId = preferences.overrideThemeId.takeUnless { it.isNullOrBlank() }
        val candidateId = localThemeId ?: syncedDefaultThemeId ?: BUILT_IN_THEME_ID
        val activeTheme = themes.firstOrNull { it.id == candidateId } ?: builtIn
        val messages = buildList {
            addAll(loadMessages)
            if (!syncedDefaultThemeId.isNullOrBlank() && activeTheme.id != syncedDefaultThemeId && localThemeId == null) {
                add("Synced theme '$syncedDefaultThemeId' was not found or is invalid. Using ${builtIn.name}.")
            }
            if (!localThemeId.isNullOrBlank() && activeTheme.id != localThemeId) {
                add("Tablet theme '$localThemeId' was not found or is invalid. Using ${activeTheme.name}.")
            }
        }
        return KKCThemeCatalog(
            themes = themes,
            activeTheme = activeTheme,
            syncedDefaultThemeId = syncedDefaultThemeId,
            invalidThemes = invalidThemes,
            loadMessages = messages,
            followSyncedDefault = preferences.followSyncedDefault,
            overrideThemeId = preferences.overrideThemeId
        )
    }

    fun setFollowSyncedDefault(value: Boolean) {
        preferences.followSyncedDefault = value
    }

    fun setOverrideThemeId(themeId: String?) {
        preferences.overrideThemeId = themeId?.takeUnless { it.isBlank() }
    }

    private fun parseThemeFile(file: File, loadMessages: MutableList<String>): Result<KKCThemeDefinition> {
        return runCatching {
            val root = gson.fromJson(file.readText(), JsonObject::class.java)
                ?: throw IllegalArgumentException("Theme file is empty")
            val id = string(root, "id")?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing id")
            val name = string(root, "name")?.takeIf { it.isNotBlank() } ?: id
            val version = int(root, "version") ?: 1
            val light = palette(root, "light")
            val dark = palette(root, "dark")
            val statusObj = root.getAsJsonObject("status")
            // Each derived bg/border reads its own dedicated key first, then falls back to
            // the base status key, then to the built-in default. This lets a theme JSON set
            // distinct shades (e.g. a soft completeBg with a darker completeBorder) instead of
            // forcing all three to the single base color.
            val lightStatus = LightStatusColors.copy(
                complete = color(statusObj, "complete") ?: LightStatusColors.complete,
                completeBg = color(statusObj, "completeBg") ?: color(statusObj, "complete") ?: LightStatusColors.completeBg,
                completeBorder = color(statusObj, "completeBorder") ?: color(statusObj, "complete") ?: LightStatusColors.completeBorder,
                bad = color(statusObj, "bad") ?: LightStatusColors.bad,
                badBg = color(statusObj, "badBg") ?: color(statusObj, "bad") ?: LightStatusColors.badBg,
                skip = color(statusObj, "skip") ?: LightStatusColors.skip,
                skipBg = color(statusObj, "skipBg") ?: color(statusObj, "skip") ?: LightStatusColors.skipBg,
                skipBorder = color(statusObj, "skipBorder") ?: color(statusObj, "skip") ?: LightStatusColors.skipBorder,
                inProgress = color(statusObj, "inProgress") ?: LightStatusColors.inProgress,
                inProgressBorder = color(statusObj, "inProgressBorder") ?: color(statusObj, "inProgress") ?: LightStatusColors.inProgressBorder
            )
            val darkStatus = DarkStatusColors.copy(
                complete = color(statusObj, "complete") ?: DarkStatusColors.complete,
                completeBg = color(statusObj, "completeBg") ?: color(statusObj, "complete") ?: DarkStatusColors.completeBg,
                completeBorder = color(statusObj, "completeBorder") ?: color(statusObj, "complete") ?: DarkStatusColors.completeBorder,
                bad = color(statusObj, "bad") ?: DarkStatusColors.bad,
                badBg = color(statusObj, "badBg") ?: color(statusObj, "bad") ?: DarkStatusColors.badBg,
                skip = color(statusObj, "skip") ?: DarkStatusColors.skip,
                skipBg = color(statusObj, "skipBg") ?: color(statusObj, "skip") ?: DarkStatusColors.skipBg,
                skipBorder = color(statusObj, "skipBorder") ?: color(statusObj, "skip") ?: DarkStatusColors.skipBorder,
                inProgress = color(statusObj, "inProgress") ?: DarkStatusColors.inProgress,
                inProgressBorder = color(statusObj, "inProgressBorder") ?: color(statusObj, "inProgress") ?: DarkStatusColors.inProgressBorder
            )
            val surfaceObj = root.getAsJsonObject("surface")
            val headerObj = root.getAsJsonObject("header")
            val frostedObj = root.getAsJsonObject("frosted")
            val shapeObj = root.getAsJsonObject("shape")
            KKCThemeDefinition(
                id = id,
                name = name,
                version = version,
                tokens = KKCThemeTokens(
                    id = id,
                    name = name,
                    light = light,
                    dark = dark,
                    lightStatus = lightStatus,
                    darkStatus = darkStatus,
                    surface = KKCThemeSurfaceTokens(
                        cardAlpha = float(surfaceObj, "cardAlpha") ?: BuiltInKKCThemeTokens.surface.cardAlpha,
                        headerTintAlpha = float(surfaceObj, "headerTintAlpha") ?: BuiltInKKCThemeTokens.surface.headerTintAlpha
                    ),
                    header = headerTokens(headerObj, file.parentFile ?: themeDir(baseDir), loadMessages),
                    frosted = KKCThemeFrostedTokens(
                        backgroundAlpha = float(frostedObj, "backgroundAlpha") ?: BuiltInKKCThemeTokens.frosted.backgroundAlpha,
                        blurDp = float(frostedObj, "blurDp") ?: BuiltInKKCThemeTokens.frosted.blurDp
                    ),
                    shape = KKCThemeShapeTokens(
                        smallDp = float(shapeObj, "smallDp") ?: BuiltInKKCThemeTokens.shape.smallDp,
                        mediumDp = float(shapeObj, "mediumDp") ?: BuiltInKKCThemeTokens.shape.mediumDp,
                        largeDp = float(shapeObj, "largeDp") ?: BuiltInKKCThemeTokens.shape.largeDp
                    ),
                    spacingScale = float(root, "spacingScale") ?: BuiltInKKCThemeTokens.spacingScale
                )
            )
        }
    }

    private fun headerTokens(
        obj: JsonObject?,
        themeDir: File,
        loadMessages: MutableList<String>
    ): KKCThemeHeaderTokens {
        val background = string(obj, "background")?.trim()
        val resolvedPath = if (background.isNullOrBlank()) {
            null
        } else {
            resolveHeaderBackground(themeDir, background, loadMessages)
        }
        return KKCThemeHeaderTokens(
            backgroundPath = resolvedPath,
            alpha = (float(obj, "alpha") ?: BuiltInKKCThemeTokens.header.alpha).coerceIn(0f, 1f),
            contentScale = headerContentScale(string(obj, "contentScale"))
        )
    }

    private fun resolveHeaderBackground(
        themeDir: File,
        relativePath: String,
        loadMessages: MutableList<String>
    ): String? {
        if (!relativePath.endsWith(".svg", ignoreCase = true)) {
            loadMessages += "Header background '$relativePath' is not an SVG. Using header gradient fallback."
            return null
        }
        val root = themeDir.canonicalFile
        val file = File(root, relativePath).canonicalFile
        // Separator-aware containment: a raw startsWith would let a sibling like
        // "<root>-evil/x.svg" pass because its path shares the "<root>" prefix.
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) {
            loadMessages += "Header background '$relativePath' points outside the theme folder. Using header gradient fallback."
            return null
        }
        if (!file.isFile) {
            loadMessages += "Header background '$relativePath' was not found. Using header gradient fallback."
            return null
        }
        return file.absolutePath
    }

    private fun headerContentScale(value: String?): KKCThemeHeaderContentScale {
        return when (value?.trim()?.lowercase()) {
            "fit" -> KKCThemeHeaderContentScale.FIT
            "fillwidth", "fill_width", "fill-width" -> KKCThemeHeaderContentScale.FILL_WIDTH
            else -> KKCThemeHeaderContentScale.CROP
        }
    }

    private fun readSyncedDefault(themeDir: File): String? {
        val activeFile = File(themeDir, ACTIVE_THEME_FILE)
        if (!activeFile.isFile) return null
        return runCatching {
            val root = gson.fromJson(activeFile.readText(), JsonObject::class.java)
            string(root, "themeId")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun palette(root: JsonObject, key: String): KKCThemePalette {
        val obj = root.getAsJsonObject(key) ?: throw IllegalArgumentException("Missing $key")
        return KKCThemePalette(
            primary = requiredColor(obj, "primary", "$key.primary"),
            background = requiredColor(obj, "background", "$key.background"),
            surface = requiredColor(obj, "surface", "$key.surface")
        )
    }

    private fun requiredColor(obj: JsonObject, key: String, label: String): Color {
        return color(obj, key) ?: throw IllegalArgumentException("Invalid $label")
    }

    private fun color(obj: JsonObject?, key: String): Color? {
        val value = string(obj, key) ?: return null
        if (!HEX_COLOR.matches(value)) return null
        val normalized = value.removePrefix("#")
        val argb = when (normalized.length) {
            6 -> "FF$normalized"
            8 -> normalized
            else -> return null
        }
        return Color(argb.toLong(16).toInt())
    }

    private fun string(obj: JsonObject?, key: String): String? {
        val value = obj?.get(key) ?: return null
        return runCatching {
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
        }.getOrNull()
    }

    private fun int(obj: JsonObject?, key: String): Int? {
        val value = obj?.get(key) ?: return null
        return runCatching {
            if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asInt else null
        }.getOrNull()
    }

    private fun float(obj: JsonObject?, key: String): Float? {
        val value = obj?.get(key) ?: return null
        return runCatching {
            if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asFloat else null
        }.getOrNull()
    }

    companion object {
        const val BUILT_IN_THEME_ID = "kkc-default"
        private const val THEME_DIR = ".metadata/themes"
        private const val ACTIVE_THEME_FILE = "active_theme.json"
        private val HEX_COLOR = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

        fun builtInThemeDefinition(): KKCThemeDefinition {
            return KKCThemeDefinition(
                id = BuiltInKKCThemeTokens.id,
                name = BuiltInKKCThemeTokens.name,
                version = 1,
                tokens = BuiltInKKCThemeTokens
            )
        }

        fun builtInCatalog(): KKCThemeCatalog {
            val theme = builtInThemeDefinition()
            return KKCThemeCatalog(
                themes = listOf(theme),
                activeTheme = theme,
                syncedDefaultThemeId = null,
                invalidThemes = emptyList(),
                loadMessages = emptyList(),
                followSyncedDefault = true,
                overrideThemeId = null
            )
        }
    }

    private fun themeDir(baseDir: File): File = File(baseDir, THEME_DIR)
}
