package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
        dataStore.edit { prefs ->
            prefs[KEY_TYPE] = config.type.name
            prefs[KEY_COLOR] = config.color
            if (config.mediaPath != null) {
                prefs[KEY_MEDIA_PATH] = config.mediaPath
            } else {
                prefs.remove(KEY_MEDIA_PATH)
            }
        }
    }

    companion object {
        private val KEY_TYPE = stringPreferencesKey("bg_type")
        private val KEY_COLOR = intPreferencesKey("bg_color")
        private val KEY_MEDIA_PATH = stringPreferencesKey("bg_media_path")

        fun bgDir(context: Context) =
            context.filesDir.resolve("timeclock_bg").also { it.mkdirs() }
    }
}
