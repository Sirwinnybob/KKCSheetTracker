package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.timecardBgDataStore by preferencesDataStore(name = "timeclock_background")

enum class TimecardBgType { NONE, COLOR, IMAGE, VIDEO }

data class TimecardBgConfig(
    val type: TimecardBgType = TimecardBgType.NONE,
    val color: Int = 0,
    val mediaPath: String? = null
)

class TimecardBgStore(private val context: Context) {

    private val dataStore = context.timecardBgDataStore

    val configFlow: Flow<TimecardBgConfig> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            TimecardBgConfig(
                type = prefs[KEY_TYPE]
                    ?.runCatching { TimecardBgType.valueOf(this) }
                    ?.getOrNull() ?: TimecardBgType.NONE,
                color = prefs[KEY_COLOR] ?: 0,
                mediaPath = prefs[KEY_MEDIA_PATH]?.takeIf { it.isNotBlank() }
            )
        }

    suspend fun save(config: TimecardBgConfig) {
        val previousMediaPath = dataStore.data.first()[KEY_MEDIA_PATH]
        dataStore.edit { prefs ->
            prefs[KEY_TYPE] = config.type.name
            prefs[KEY_COLOR] = config.color
            if (config.mediaPath != null) {
                prefs[KEY_MEDIA_PATH] = config.mediaPath
            } else {
                prefs.remove(KEY_MEDIA_PATH)
            }
        }
        // Orphan cleanup: the previous media file is no longer referenced by config once its
        // path differs from the newly-saved one. Only ever delete files that live inside our
        // own background-media directory, so a still-selected path (unchanged save) or any
        // path outside bgDir is never touched.
        deleteOrphanedMedia(bgDir(context), previousMediaPath, config.mediaPath)
    }

    companion object {
        private val KEY_TYPE = stringPreferencesKey("bg_type")
        private val KEY_COLOR = intPreferencesKey("bg_color")
        private val KEY_MEDIA_PATH = stringPreferencesKey("bg_media_path")

        fun bgDir(context: Context) =
            context.filesDir.resolve("timeclock_bg").also { it.mkdirs() }

        /**
         * Deletes [previousPath] from disk iff it is non-null, differs from [newPath] (i.e. is no
         * longer the selected media), and resolves to a direct child of [mediaDir] (i.e. it's one
         * of our own copied background files, never an arbitrary path). Pure/testable without a
         * Context or DataStore.
         */
        internal fun deleteOrphanedMedia(mediaDir: File, previousPath: String?, newPath: String?): Boolean {
            if (previousPath.isNullOrBlank() || previousPath == newPath) return false
            val dirCanonical = runCatching { mediaDir.canonicalFile }.getOrNull() ?: return false
            val fileCanonical = runCatching { File(previousPath).canonicalFile }.getOrNull() ?: return false
            if (fileCanonical.parentFile != dirCanonical) return false
            return fileCanonical.delete()
        }
    }
}
